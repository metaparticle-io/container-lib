package io.metaparticle.containerlib.elector;

import com.google.gson.annotations.SerializedName;
import com.squareup.okhttp.Call;
import com.squareup.okhttp.Response;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.Pair;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.apis.ApiextensionsV1beta1Api;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1PodList;
import io.kubernetes.client.models.V1beta1CustomResourceDefinition;
import io.kubernetes.client.models.V1beta1CustomResourceDefinitionNames;
import io.kubernetes.client.models.V1beta1CustomResourceDefinitionSpec;
import io.kubernetes.client.util.Config;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import org.joda.time.DateTime;
import org.joda.time.Duration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class Main {
    public static void createResourceIfNotExists() throws IOException, ApiException {
        V1ObjectMeta meta = new V1ObjectMeta();
        meta.setName("locks.metaparticle.io");

        ApiextensionsV1beta1Api ext = new ApiextensionsV1beta1Api();
        V1beta1CustomResourceDefinition crd;

        try {
            crd = ext.readCustomResourceDefinition(meta.getName(), null, null, null);
            // If the above doesn't throw, the resource already exists, so just return.
            return;
        } catch (ApiException ex) {
            if (ex.getCode() != 404) {
                throw ex;
            }
        }
        crd = new V1beta1CustomResourceDefinition();

        crd.setMetadata(meta);

        V1beta1CustomResourceDefinitionSpec spec = new V1beta1CustomResourceDefinitionSpec();
        spec.setGroup("metaparticle.io");
        spec.setVersion("v1");

        V1beta1CustomResourceDefinitionNames names = new V1beta1CustomResourceDefinitionNames();
        names.setKind("Lock");
        names.setListKind("LockList");
        names.setPlural("locks");

        spec.setNames(names);
        crd.setSpec(spec);
        ext.createCustomResourceDefinition(crd, "false");
    }

    public static class LockSpec {
        @SerializedName("owner")
        private String owner = null;

        @SerializedName("expiry")
        private String expiry = null;
    }

    public static class Lock {
        @SerializedName("metadata")
        private V1ObjectMeta metadata = null;

        @SerializedName("kind")
        private String kind = "Lock";

        @SerializedName("apiVersion")
        private String apiVersion = "metaparticle.io/v1";

        @SerializedName("spec")
        private LockSpec spec;
    }

    public static Lock createLock(ApiClient client, Lock l) throws IOException, ApiException {
        String path = "/apis/metaparticle.io/v1/namespaces/default/locks";
        String method = "POST";
        List<Pair> empty = new ArrayList<Pair>();

        Call call = client.buildCall(path, method, empty, empty, l, new HashMap<String, String>(),
                new HashMap<String, Object>(), new String[0], null);
        return client.handleResponse(call.execute(), Lock.class);
    }

    public static Lock getLock(ApiClient client, String name) throws IOException, ApiException {
        String path = "/apis/metaparticle.io/v1/namespaces/default/locks/" + name;
        String method = "GET";
        List<Pair> empty = new ArrayList<Pair>();

        Call call = client.buildCall(path, method, empty, empty, null, new HashMap<String, String>(),
                new HashMap<String, Object>(), new String[0], null);
        return client.handleResponse(call.execute(), Lock.class);
    }

    public static Lock updateLock(ApiClient client, Lock l) throws IOException, ApiException {
        String path = "/apis/metaparticle.io/v1/namespaces/default/locks/" + l.metadata.getName();
        String method = "PUT";
        List<Pair> empty = new ArrayList<Pair>();

        Call call = client.buildCall(path, method, empty, empty, l, new HashMap<String, String>(),
                new HashMap<String, Object>(), new String[0], null);
        return client.handleResponse(call.execute(), Lock.class);
    }

    public static void sendResponse(int code, String msg, HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(code, msg.length());
        OutputStream os = exchange.getResponseBody();
        os.write(msg.getBytes());
        os.close();
    }

    public static void handleLockServe(ApiClient client, String hostname, String lockName, long ttl, HttpExchange t)
            throws IOException {
        try {
            Lock l = null;
            try {
                l = getLock(client, lockName);
            } catch (ApiException ex) {
                if (ex.getCode() != 404) {
                    throw ex;
                }
            }
            switch (t.getRequestMethod()) {
            case "GET":
                if (l == null) {
                    sendResponse(404, "Not found.", t);
                } else {
                    sendResponse(200, l.toString(), t);
                }
                break;
            case "PUT":
                if (l == null) {
                    l = new Lock();
                    V1ObjectMeta meta = new V1ObjectMeta();
                    meta.setName(lockName);
                    meta.setNamespace("default");
                    l.metadata = meta;

                    LockSpec spec = new LockSpec();
                    spec.owner = hostname;
                    spec.expiry = new DateTime().plus(ttl).toString();
                    l.spec = spec;

                    System.out.println("Creating lock owned by " + l.spec.owner);
                    l = createLock(client, l);
                    sendResponse(200, l.toString(), t);
                    return;
                }
                System.out.println("Lock owned by: " + l.spec.owner);
                DateTime expires = DateTime.parse(l.spec.expiry);
                Duration d = new Duration(new DateTime(), expires);
                System.out.println("Expires in " + d);
                if (d.getMillis() < 0 || (l.spec.owner.equals(hostname) && d.getMillis() < (ttl / 2))) {
                    System.out.println("Updating lock to be owned by: " + hostname);
                    l.spec.owner = hostname;
                    l.spec.expiry = new DateTime().plus(ttl).toString();
                    l = updateLock(client, l);
                    sendResponse(200, l.toString(), t);
                    return;
                } else {
                    sendResponse(200, l.toString(), t);
                }
            }
        } catch (ApiException ex) {
            sendResponse(ex.getCode(), ex.toString(), t);
        } catch (IOException ex) {
            sendResponse(500, "An error occurred: " + ex, t);
        }
    }

    public static void main(String[] args) throws IOException, ApiException {
        final ApiClient client = Config.defaultClient();
        Configuration.setDefaultApiClient(client);

        createResourceIfNotExists();

        String host = InetAddress.getLocalHost().getHostName();
        if (args.length > 0) {
            host = args[0];
        }
        final String hostname = host;
        final long ttl = 30 * 1000;

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange t) throws IOException {
                String path = t.getRequestURI().getPath();
                if (!path.startsWith("locks")) {
                    sendResponse(404, "Unknown path: " + path, t);
                    return;
                }
                String[] parts = path.split("/");
                if (parts.length != 2) {
                    sendResponse(429, "Bad path: " + path, t);
                    return;
                }
                handleLockServe(client, hostname, parts[1], ttl, t);
            }
        });
    }
}
/*
                String msg = "Hello Velocity [" + t.getRequestURI() + "] from " + System.getenv("HOSTNAME");
                t.sendResponseHeaders(200, msg.length());
                OutputStream os = t.getResponseBody();
                os.write(msg.getBytes());
                os.close();
                System.out.println("[" + t.getRequestURI() + "]");
            }
        });
/*
        Lock l = null;
        
        while (true) {
            try {
                l = getLock(client, "somelock");
            } catch (ApiException ex) {
                if (ex.getCode() != 404) {
                    throw ex;
                }
            }
            if (l == null) {
                l = new Lock();
                V1ObjectMeta meta = new V1ObjectMeta();
                meta.setName("somelock");
                meta.setNamespace("default");
                l.metadata = meta;
            
                LockSpec spec = new LockSpec();
                spec.owner = hostname;
                spec.expiry = new DateTime().plus(ttl).toString();
                l.spec = spec;

                System.out.println("Creating lock owned by " + l.spec.owner);            
                l = createLock(client, l);
            } else {
                System.out.println("Lock owned by: " + l.spec.owner);
                DateTime expires = DateTime.parse(l.spec.expiry);
                Duration d = new Duration(new DateTime(), expires);
                System.out.println("Expires in " + d);
                if (d.getMillis() < 0 || (l.spec.owner.equals(hostname) && d.getMillis() < (ttl/2))) {
                    System.out.println("Updating lock to be owned by: " + hostname);
                    l.spec.owner = hostname;    
                    l.spec.expiry = new DateTime().plus(ttl).toString();
                    try {
                        l = updateLock(client, l);
                    } catch (ApiException ex) {
                        if (ex.getCode() != 409 && ex.getCode() != 404) {
                            ex.printStackTrace();
                        }
                    }               
                } else {
                    System.out.println("Lock still owned by " + l.spec.owner);
                }
            }
            Duration d = new Duration(new DateTime(), DateTime.parse(l.spec.expiry));
            long interval = d.getMillis();
            if (l.spec.owner.equals(hostname)) {
                interval = interval / 3;
            }
            System.out.println("sleeping for " + interval);
            try {        
                Thread.sleep(interval);
            } catch (InterruptedException ex) {
            }
        }
        */

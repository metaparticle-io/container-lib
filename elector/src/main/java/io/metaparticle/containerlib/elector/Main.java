package io.metaparticle.containerlib.elector;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.apis.ApiextensionsV1beta1Api;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1beta1CustomResourceDefinition;
import io.kubernetes.client.models.V1beta1CustomResourceDefinitionNames;
import io.kubernetes.client.models.V1beta1CustomResourceDefinitionSpec;
import io.kubernetes.client.util.Config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import org.joda.time.DateTime;
import org.joda.time.Duration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class Main {
    public static class Error {
        @SerializedName("message")
        public String msg = null;
    }

    public static String error(Gson gson, String msg) {
        Error err = new Error();
        err.msg = msg;
        return gson.toJson(err);
    }

    public static void sendResponse(int code, String msg, HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(code, msg.length());
        OutputStream os = exchange.getResponseBody();
        os.write(msg.getBytes());
        os.close();
    }

    private static Lock createLock(String lockName, String owner, long ttl) {
        Lock l = new Lock();
        V1ObjectMeta meta = new V1ObjectMeta();
        meta.setName(lockName);
        meta.setNamespace("default");
        l.metadata = meta;

        Lock.LockSpec spec = new Lock.LockSpec();
        spec.owner = owner;
        spec.expiry = new DateTime().plus(ttl).toString();
        l.spec = spec;

        System.out.println("Creating lock owned by " + l.spec.owner);

        return l;
    }

    public static void handleLockServe(Gson gson, StorageInterface client, String hostname, String lockName, long ttl, HttpExchange t)
            throws IOException {
        try {
            Lock l = null;
            try {
                l = client.getLock(lockName);
            } catch (ApiException ex) {
                if (ex.getCode() != 404) {
                    throw ex;
                }
            }
            switch (t.getRequestMethod()) {
            case "GET":
                if (l == null) {
                    sendResponse(404, error(gson, "Not found."), t);
                } else {
                    sendResponse(200, gson.toJson(l), t);
                }
                break;
            case "POST":
                if (l != null) {
                    sendResponse(409, error(gson, "Conflict"), t);
                    return;
                }
                l = createLock(lockName, hostname, ttl);
                l = client.createLock(l);
                sendResponse(200, gson.toJson(l), t);
                break;
            case "PUT":
                if (l == null) {
                    l = createLock(lockName, hostname, ttl);
                    l = client.createLock(l);
                    sendResponse(200, gson.toJson(l), t);
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
                    l = client.updateLock(l);
                    sendResponse(200, gson.toJson(l), t);
                    return;
                } else if (l.spec.owner.equals(hostname)) {
                    sendResponse(200, gson.toJson(l), t);
                } else {
                    sendResponse(409,  gson.toJson(l), t);
                }
                break;
            }
        } catch (ApiException ex) {
            sendResponse(ex.getCode(), error(gson, ex.toString()), t);
        } catch (IOException ex) {
            sendResponse(500, error(gson, "An error occurred: " + ex), t);
        }
    }

    public static void newLockServer(final Gson gson, final StorageInterface client, int port, final String hostname, final long ttl)
        throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange t) throws IOException {
                System.out.println(t.getRequestURI());
                String path = t.getRequestURI().getPath();
                if (!path.startsWith("/locks")) {
                    sendResponse(404, error(gson, "Unknown path: " + path), t);
                    return;
                }
                String[] parts = path.split("/");
                String name = null;
                switch (parts.length) {
                    case 2:
                        InputStream is = t.getRequestBody();
                        Lock l = gson.fromJson(new InputStreamReader(is), Lock.class);
                        name = l.metadata.getName();
                        is.close();
                        break;
                    case 3:
                        name = parts[2];
                        break;
                    default:
                        sendResponse(429, error(gson, "Bad path: " + path), t);
                        return;
                }
                System.out.println("Lock name is: " + name);
                handleLockServe(gson, client, hostname, name, ttl, t);
            }
        });
        server.start();
    }

    public static void main(String[] args) throws IOException, ApiException {
        final Gson gson = new Gson();
        final ApiClient k8sClient = Config.defaultClient();
        Configuration.setDefaultApiClient(k8sClient);
        //final StorageInterface client = new KubernetesStorage(k8sClient);
        //String host = InetAddress.getLocalHost().getHostName();

        final StorageInterface client = new MemoryStorage();
        String host = "foo";

        if (args.length > 0) {
            host = args[0];
        }
        final String hostname = host;
        final long ttl = 30 * 1000;

        new Thread(new Runnable() {
            public void run() {
                try {
                    newLockServer(gson, client, 8080, hostname, ttl);
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }).start();
        newLockServer(gson, client, 8090, hostname + "baz", ttl);
    }
}

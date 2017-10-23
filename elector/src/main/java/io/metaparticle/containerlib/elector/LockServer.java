package io.metaparticle.containerlib.elector;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1ObjectMeta;

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

public class LockServer implements Runnable {
    public static class Error {
        @SerializedName("message")
        public String msg = null;
    }

    private Gson gson;
    private StorageInterface client;
    private String hostname;
    private long ttl;
    private int port;

    public LockServer(StorageInterface client, String hostname, long ttl, int port) {
        this.gson = new Gson();
        this.client = client;
        this.hostname = hostname;
        this.ttl = ttl;
        this.port = port;
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

    protected static String validateRequest(Gson gson, HttpExchange t) throws IOException {
        String path = t.getRequestURI().getPath();
        if (!path.startsWith("/locks")) {
            sendResponse(404, error(gson, "Unknown path: " + path), t);
            return null;
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
            return null;
        }
        return name;
    }

    public void handleLockServe(String lockName, HttpExchange t) throws IOException {
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
                    sendResponse(409, gson.toJson(l), t);
                }
                break;
            }
        } catch (ApiException ex) {
            ex.printStackTrace();
            sendResponse(ex.getCode(), error(gson, ex.toString()), t);
        } catch (IOException ex) {
            ex.printStackTrace();
            sendResponse(500, error(gson, "An error occurred: " + ex), t);
        }
    }

    public void run() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", new HttpHandler() {
                @Override
                public void handle(HttpExchange t) throws IOException {
                    System.out.println(t.getRequestURI());
                    String name = LockServer.validateRequest(LockServer.this.gson, t);
                    if (name == null) {
                        return;
                    }
                    System.out.println("Lock name is: " + name);
                    LockServer.this.handleLockServe(name, t);
                }
            });
            server.start();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public static LockServer newLockServer(final StorageInterface client, int port,
            final String hostname, final long ttl) throws IOException {
        LockServer lockServe = new LockServer(client, hostname, ttl, port);
        lockServe.run();
        return lockServe;
    }
}
package io.metaparticle;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import java.io.IOException;
import java.util.Random;

public class Lock {
    // TODO: Switch to a threadpool here?
    private Thread maintainer;
    private String name;
    private boolean running;
    private String baseUri;
    private LockListener listener;

    // For testing only!
    // If true, the lock will purposesfully fail to update
    // itself (and thus loose the lock) 50% of the time.
    private boolean flaky;
    private Random rand;

    public Lock(String name) {
        this(name, "http://localhost:8080");
    }

    public Lock(String name, String baseUri) {
        this.name = name;
        this.baseUri = baseUri;
        this.listener = null;
    }

    // Make this lock flaky (e.g. lose the lock)
    // Warning, should only be used for testing.
    // Requires a lock listener, or the lock will exist.
    public void setFlakyLockForTesting() {
        flaky = true;
        rand = new Random();
    }

    public void setLockListener(LockListener l) {
        listener = l;
    }

    public synchronized void lock() throws InterruptedException {
        if (maintainer != null) {
            throw new IllegalStateException("Locks are not re-entrant!");
        }
        while (true) {
            int code = -1;
            try {
                code = getLock(name);
                if (code == 404 || code == 200) {
                    code = updateLock(name);
                }
                if (code == 200) {
                    holdLock(name);
                    return;
                }
                if (code != 409) {
                    System.out.println("Code was: " + code);
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            // TODO: introduce watch here instead of polling...
            Thread.sleep(10 * 1000);
        }
    }

    public synchronized void unlock() {
        if (maintainer == null) {
            throw new IllegalStateException("Lock is not held.");
        }
        running = false;
        try {
            maintainer.join(10 * 1000);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }

    private int getLock(String name) throws IOException {
        try {
            HttpResponse<JsonNode> jsonResponse = Unirest.get(baseUri + "/locks/" + name)
                .header("accept", "application/json")
                .asJson();
            return jsonResponse.getStatus();
        } catch (UnirestException ex) {
            throw new IOException(ex);
        }
    }

    private int updateLock(String name) throws IOException {
        try {
            HttpResponse<JsonNode> jsonResponse = Unirest.put(baseUri + "/locks/" + name)
                .header("accept", "application/json")
                .asJson();
            return jsonResponse.getStatus();
        } catch (UnirestException ex) {
            throw new IOException(ex);
        }
    }

    private void holdLock(final String name) {
        running = true;
        if (listener != null) {
            listener.lockAcquired();
        }
        maintainer = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        int code = getLock(name);
                        if (code == 200) {
                            code = updateLock(name);
                        }
                        if (code != 200) {
                            System.out.println("Unexpected status: " + code);
                            if (listener != null) {
                                break;
                            } else {
                                System.exit(0);
                            }
                        }
                        // This is only for testing.
                        if (flaky && rand.nextBoolean()) {
                            Thread.sleep(50 * 1000);
                        } else {
                            Thread.sleep(10 * 1000);
                        }
                    } catch (IOException | InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
                maintainer = null;
                if (listener != null) {
                    listener.lockLost();
                }               
            }
        });
        maintainer.start();
    }
}
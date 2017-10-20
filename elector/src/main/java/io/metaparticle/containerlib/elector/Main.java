package io.metaparticle.containerlib.elector;

import com.google.gson.Gson;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.util.Config;

import java.io.IOException;
import java.net.InetAddress;

public class Main {
    public static void main(String[] args) throws IOException, ApiException {
        final Gson gson = new Gson();
        final ApiClient k8sClient = Config.defaultClient();
        Configuration.setDefaultApiClient(k8sClient);
        final StorageInterface client = new KubernetesStorage(k8sClient);
        String host = InetAddress.getLocalHost().getHostName();

        //final StorageInterface client = new MemoryStorage();
        //String host = "foo";

        if (args.length > 0) {
            host = args[0];
        }
        final String hostname = host;
        final long ttl = 30 * 1000;

        LockServer ls = new LockServer(client, hostname, ttl, 8080);        
        new Thread(ls).start();
        
        // This is just for debugging....
        LockServer ls2 = new LockServer(client, hostname + "baz", ttl, 8090);
        ls2.run();
    }
}

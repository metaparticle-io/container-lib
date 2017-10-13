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
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Pair;

import org.joda.time.DateTime;
import org.joda.time.Duration;

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
        
        Call call = client.buildCall(path, method, empty, empty, l, new HashMap<String, String>(), new HashMap<String, Object>(), new String[0], null);
        return client.handleResponse(call.execute(), Lock.class);
    }

    public static Lock getLock(ApiClient client, String name) throws IOException, ApiException {
        String path = "/apis/metaparticle.io/v1/namespaces/default/locks/" + name;
        String method = "GET";
        List<Pair> empty = new ArrayList<Pair>();
        
        Call call = client.buildCall(path, method, empty, empty, null, new HashMap<String, String>(), new HashMap<String, Object>(), new String[0], null);
        return client.handleResponse(call.execute(), Lock.class);

    }

    public static void main(String[] args) throws IOException, ApiException {
        ApiClient client = Config.defaultClient();
        Configuration.setDefaultApiClient(client);

        createResourceIfNotExists();

        Lock l = null;
        
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
            System.out.println("Hostname is " + InetAddress.getLocalHost().getHostName());
            spec.owner = InetAddress.getLocalHost().getHostName();
            spec.expiry = new DateTime().plus(30 * 1000).toString();
            l.spec = spec;

            System.out.println("Creating lock owned by " + l.spec.owner);            
            l = createLock(client, l);
        } else {
            System.out.println("Lock owned by: " + l.spec.owner);
            DateTime expires = DateTime.parse(l.spec.expiry);
            Duration d =new Duration(new DateTime(), expires);
            System.out.println("Expires in " + d);
        }
    }
}
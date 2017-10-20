package io.metaparticle.containerlib.elector;

import com.squareup.okhttp.Call;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.Pair;
import io.kubernetes.client.apis.ApiextensionsV1beta1Api;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1beta1CustomResourceDefinition;
import io.kubernetes.client.models.V1beta1CustomResourceDefinitionNames;
import io.kubernetes.client.models.V1beta1CustomResourceDefinitionSpec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class KubernetesStorage implements StorageInterface {
    ApiClient client;

    public KubernetesStorage(ApiClient client) throws IOException, ApiException {
        this.client = client;
        createResourceIfNotExists(client);
    }

    public static void createResourceIfNotExists(ApiClient client) throws IOException, ApiException {
        V1ObjectMeta meta = new V1ObjectMeta();
        meta.setName("locks.metaparticle.io");

        ApiextensionsV1beta1Api ext = new ApiextensionsV1beta1Api(client);
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

    @Override
    public Lock createLock(Lock l) throws ApiException, IOException  {
        String path = "/apis/metaparticle.io/v1/namespaces/default/locks";
        String method = "POST";
        List<Pair> empty = new ArrayList<Pair>();

        Call call = client.buildCall(path, method, empty, empty, l, new HashMap<String, String>(),
                new HashMap<String, Object>(), new String[0], null);
        return client.handleResponse(call.execute(), Lock.class);
    }

    @Override
    public Lock updateLock(Lock l) throws ApiException, IOException {
        String path = "/apis/metaparticle.io/v1/namespaces/default/locks/" + l.metadata.getName();
        String method = "PUT";
        List<Pair> empty = new ArrayList<Pair>();

        Call call = client.buildCall(path, method, empty, empty, l, new HashMap<String, String>(),
                new HashMap<String, Object>(), new String[0], null);
        return client.handleResponse(call.execute(), Lock.class);
    }

    @Override
    public Lock getLock(String name) throws ApiException, IOException {
        String path = "/apis/metaparticle.io/v1/namespaces/default/locks/" + name;
        String method = "GET";
        List<Pair> empty = new ArrayList<Pair>();

        Call call = client.buildCall(path, method, empty, empty, null, new HashMap<String, String>(),
                new HashMap<String, Object>(), new String[0], null);
        return client.handleResponse(call.execute(), Lock.class);
    }
}
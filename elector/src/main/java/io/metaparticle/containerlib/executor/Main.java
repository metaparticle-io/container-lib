package io.metaparticle.containerlib.executor;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.Configuration;
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

public class Main {
    public static void createResourceIfNotExists() throws IOException, ApiException {
        V1ObjectMeta meta = new V1ObjectMeta();
        meta.setName("locks.metaparticle.io");

        ApiextensionsV1beta1Api ext = new ApiextensionsV1beta1Api();
        V1beta1CustomResourceDefinition crd;
        
        try {
            crd = ext.readCustomResourceDefinition(meta.getName(), null, null, null);
        } catch (ApiException ex) {
            if (ex.getCode() != 404) {
                throw ex;
            }
        }
        crd = new V1beta1CustomResourceDefinition();
        
        crd.setMetadata(meta);

        V1beta1CustomResourceDefinitionSpec spec = new V1beta1CustomResourceDefinitionSpec();
        spec.setGroup("kubernetes.io");
        spec.setVersion("v1");

        V1beta1CustomResourceDefinitionNames names = new V1beta1CustomResourceDefinitionNames();
        names.setKind("Lock");
        names.setListKind("LockList");
        names.setPlural("locks");

        spec.setNames(names);
        crd.setSpec(spec);
        ext.createCustomResourceDefinition(crd, "false");
    }

    public static void main(String[] args) throws IOException, ApiException {
        ApiClient client = Config.defaultClient();
        Configuration.setDefaultApiClient(client);

        createResourceIfNotExists();

        String path = "/api/kubernetes.io/v1/namespaces/default/locks/SomeLock";
        String method = "PUT";
        client.buildCall(path, method, null, null, "{ \"foo\": 1 }", null, null, null, null);
    }
}
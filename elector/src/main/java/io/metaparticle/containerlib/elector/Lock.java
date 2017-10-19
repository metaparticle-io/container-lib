package io.metaparticle.containerlib.elector;

import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.models.V1ObjectMeta;

public class Lock {
    public static class LockSpec {
        @SerializedName("owner")
        public String owner = null;

        @SerializedName("expiry")
        public String expiry = null;
    }

    @SerializedName("metadata")
    public V1ObjectMeta metadata = null;

    @SerializedName("kind")
    private String kind = "Lock";

    @SerializedName("apiVersion")
    private String apiVersion = "metaparticle.io/v1";

    @SerializedName("spec")
    public LockSpec spec;
}
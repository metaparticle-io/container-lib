package io.metaparticle.containerlib.elector;

import io.kubernetes.client.ApiException;
import java.io.IOException;

public interface StorageInterface {
    public Lock createLock(Lock l) throws ApiException, IOException;
    public Lock updateLock(Lock l) throws ApiException, IOException;
    public Lock getLock(String name) throws ApiException, IOException;
}

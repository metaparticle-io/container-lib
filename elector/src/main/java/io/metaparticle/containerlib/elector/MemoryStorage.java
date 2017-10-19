package io.metaparticle.containerlib.elector;

import io.kubernetes.client.ApiException;

import java.io.IOException;
import java.util.HashMap;

public class MemoryStorage implements StorageInterface {
    HashMap<String, Lock> map;
    public MemoryStorage() {
        map = new HashMap<>();
    }

    @Override
    public Lock createLock(Lock l) throws ApiException, IOException  {
        String key = l.metadata.getName();
        synchronized (map) {
            if (map.containsKey(key)) {
                throw new ApiException(409, "Lock exists!");
            }
            l.metadata.resourceVersion("0");
            map.put(key, l);
        }
        return l;
    }

    @Override
    public Lock updateLock(Lock l) throws ApiException, IOException {
        String key = l.metadata.getName();
        synchronized (map) {
            Lock curr = map.get(key);
            if (curr == null) {
                throw new ApiException(404, "Lock doesn't exist!");
            }
            if (!curr.metadata.getResourceVersion().equals(l.metadata.getResourceVersion())) {
                throw new ApiException(409, "Conflict!");
            }
            int i = Integer.parseInt(l.metadata.getResourceVersion());
            i = i + 1;
            l.metadata.resourceVersion(Integer.toString(i));
            map.put(key, l);
            return l;            
        }
    }

    @Override
    public Lock getLock(String name) throws ApiException, IOException {
        String key = name;
        synchronized (map) {
            if (!map.containsKey(key)) {
                throw new ApiException(404, "Not found");
            }
            return map.get(name);
        }
    }
}
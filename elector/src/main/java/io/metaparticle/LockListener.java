package io.metaparticle;

public interface LockListener {
    public void lockAcquired();
    public void lockLost();
}
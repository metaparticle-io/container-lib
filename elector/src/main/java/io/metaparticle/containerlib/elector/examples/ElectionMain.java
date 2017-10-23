package io.metaparticle.containerlib.elector.examples;

import io.metaparticle.Election;

import java.io.IOException;

public class ElectionMain {
    public static void main(String[] args) throws InterruptedException {
        while (true) {
            Election e = new Election(
                "test", args[0],
                () -> {
                    System.out.println("I am the master.");
                },
                () -> {
                    System.out.println("I lost the master.");
                });
            
            // So that something exciting happens...
            e.setFlakyElectionForTesting();
            
            e.run();
            Thread.currentThread().sleep(1000);
        }
    }
}
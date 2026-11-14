package com.jellypudding.offlineclient.rig.core;

// One named check. The name says what it proves.
public record Case(String name, Body body, String knownFault) {

    @FunctionalInterface
    public interface Body {
        void run(Rig rig) throws Exception;
    }

    public static Case of(String name, Body body) {
        return new Case(name, body, null);
    }

    // Marks a case the client is known to fail today. The words say what the owner saw. A failure
    // is then reported as a known fault and a pass says the mark can come off.
    public Case known(String fault) {
        return new Case(name, body, fault);
    }
}

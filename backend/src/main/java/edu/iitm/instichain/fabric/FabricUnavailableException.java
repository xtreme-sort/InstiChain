package edu.iitm.instichain.fabric;

public final class FabricUnavailableException extends RuntimeException {
    public FabricUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public FabricUnavailableException(String message) {
        super(message);
    }
}

package dev.chronovault.core;

public class ChronoException extends RuntimeException {
    public ChronoException(String message) { super(message); }
    public ChronoException(String message, Throwable cause) { super(message, cause); }

    public static class VaultException extends ChronoException {
        public VaultException(String message) { super(message); }
        public VaultException(String message, Throwable cause) { super(message, cause); }
    }

    public static class UnsafePathException extends ChronoException {
        public UnsafePathException(String message) { super(message); }
    }

    public static class RecoveryException extends ChronoException {
        public RecoveryException(String message) { super(message); }
        public RecoveryException(String message, Throwable cause) { super(message, cause); }
    }

    public static class UnauthorizedCommandException extends ChronoException {
        public UnauthorizedCommandException(String message) { super(message); }
    }
}
package dev.chronovault.intellij;

import java.io.IOException;

/**
 * Minimal HTTP surface used by the native dashboard panel. Kept as an interface
 * so pure-JDK unit tests can supply canned responses without a live server.
 */
@FunctionalInterface
public interface DashboardApiClient {
    String request(String method, String pathAndQuery) throws IOException;
}
package dev.chronovault.intellij;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Tests for the loopback-only navigation guard (pure string logic). */
public class JcefSupportTest {

    @Test
    public void loopbackUrlsAreAllowed() {
        assertTrue(JcefSupport.isLoopbackUrl("http://127.0.0.1:51234/"));
        assertTrue(JcefSupport.isLoopbackUrl("http://127.0.0.1:51234/api/state"));
        assertTrue(JcefSupport.isLoopbackUrl("http://localhost:51234/"));
        assertTrue(JcefSupport.isLoopbackUrl("http://[::1]:51234/"));
    }

    @Test
    public void remoteUrlsAreBlocked() {
        assertFalse(JcefSupport.isLoopbackUrl("http://example.com/"));
        assertFalse(JcefSupport.isLoopbackUrl("https://example.com/"));
        assertFalse(JcefSupport.isLoopbackUrl("http://127.0.0.2:51234/"));
        assertFalse(JcefSupport.isLoopbackUrl("file:///etc/passwd"));
        assertFalse(JcefSupport.isLoopbackUrl(""));
        assertFalse(JcefSupport.isLoopbackUrl(null));
    }

    @Test
    public void reflectiveProbeAnswersConsistently() {
        // The IntelliJ platform test classpath ships the JCEF module, so the
        // probe must come back true here (and cache so clients agree).
        assertTrue(JcefSupport.isSupported(JcefSupport.class.getClassLoader()));
        assertTrue(JcefSupport.isSupported(JcefSupport.class.getClassLoader()));
    }
}
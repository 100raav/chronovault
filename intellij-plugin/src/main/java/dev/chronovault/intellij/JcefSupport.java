package dev.chronovault.intellij;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.BorderLayout;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;

/**
 * Reflective wrapper around IntelliJ's JCEF module.
 * Every external class is loaded through the plugin class loader so the core
 * source never has a compile-time dependency on JCEF — callers that run in
 * non-JCEF IDEs (older Community builds) stay binary-compatible.
 *
 * <p>Key requirement: the dashboard is only allowed to talk to the loopback
 * chronovault server.  Navigation to any other URL is cancelled by a
 * {@code CefRequestHandler} proxy installed before the initial load.
 */
public final class JcefSupport {

    private static final Logger LOG = Logger.getInstance(JcefSupport.class);
    private static volatile boolean supportProbed;
    private static volatile boolean supported;

    private JcefSupport() {}

    /** Lightweight, thread-safe probe: true when JCEF is fully on the classpath. */
    public static boolean isSupported(ClassLoader cl) {
        if (supportProbed) return supported;
        synchronized (JcefSupport.class) {
            if (supportProbed) return supported;
            try {
                cl.loadClass("com.intellij.ui.jcef.JBCefBrowser");
                cl.loadClass("org.cef.handler.CefRequestHandler");
                cl.loadClass("org.cef.handler.CefRequestHandlerAdapter");
                supported = true;
            } catch (Throwable ignored) {
                supported = false;
            }
            supportProbed = true;
            return supported;
        }
    }

    /** True when the given URL targets the loopback chronovault server and is safe to load. */
    public static boolean isLoopbackUrl(String url) {
        if (url == null) return false;
        return url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")
                || url.startsWith("http://[::1]:");
    }

    /**
     * Create a JCEF browser panel that loads the dashboard on {@code port}.
     * The returned component owns the browser and will be disposed when
     * {@code parent} is disposed.
     *
     * @throws Exception when any reflective step fails (caller must fall back).
     */
    @NotNull
    public static JComponent createView(int port,
                                        @NotNull Project project,
                                        @NotNull Disposable parent) throws Exception {
        ClassLoader cl = JcefSupport.class.getClassLoader();

        // 1. create JBCefBrowser
        Class<?> browserClass = cl.loadClass("com.intellij.ui.jcef.JBCefBrowser");
        Object browser = browserClass.getConstructor().newInstance();

        // 2. get CefBrowser + client + install loopback request handler
        installLoopbackGuard(browserClass, browser, cl);

        // 3. load the dashboard URL
        String url = "http://127.0.0.1:" + port + "/";
        Method loadUrl = browserClass.getMethod("loadURL", String.class);
        loadUrl.invoke(browser, url);

        // 4. extract Swing component
        Method getComponent = browserClass.getMethod("getComponent");
        JComponent component = (JComponent) getComponent.invoke(browser);

        // 5. ensure the browser is disposed with the project
        com.intellij.openapi.util.Disposer.register(parent, () -> {
            try {
                Method dispose = browserClass.getMethod("dispose");
                dispose.invoke(browser);
            } catch (Exception ignored) {}
        });

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(component, BorderLayout.CENTER);
        return wrapper;
    }

    // ---- internals ---------------------------------------------------------

    /**
     * Install a CefRequestHandler proxy that cancels any navigation away from
     * 127.0.0.1 / localhost / [::1].  Best-effort: if the handler API surface
     * changed between IDE versions the guard is silently skipped (the dashboard
     * CSP already blocks network loads).
     */
    private static void installLoopbackGuard(Class<?> browserClass,
                                              Object browser,
                                              ClassLoader cl) throws Exception {
        // getCefBrowser() → org.cef.browser.CefBrowser
        Class<?> cefBrowserClass = cl.loadClass("org.cef.browser.CefBrowser");
        Class<?> clientClass = cl.loadClass("org.cef.browser.CefClient");
        Class<?> requestHandlerClass = cl.loadClass("org.cef.handler.CefRequestHandler");
        Class<?> frameClass = cl.loadClass("org.cef.browser.CefFrame");
        Class<?> requestClass = cl.loadClass("org.cef.callback.CefRequest");

        Method getCefBrowser = browserClass.getMethod("getCefBrowser");
        Object cefBrowser = getCefBrowser.invoke(browser);

        Method getClient = cefBrowserClass.getMethod("getClient");
        Object client = getClient.invoke(cefBrowser);

        // Build a JDK Proxy implementing CefRequestHandler — all methods return
        // false / null by default; only onBeforeBrowse is intercepted.
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("onBeforeBrowse".equals(name) && args != null && args.length >= 3) {
                // args: CefBrowser, CefFrame, CefRequest, boolean isUserGesture
                Object req = args[2];
                try {
                    Method getUrl = requestClass.getMethod("getUrl");
                    String url = (String) getUrl.invoke(req);
                    if (!isLoopbackUrl(url)) {
                        LOG.info("CHRONOVAULT JCEF: blocked non-loopback navigation to " + url);
                        return true; // cancel
                    }
                } catch (Exception ignored) {}
            }
            // Default for all other handler methods
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) return false;
            if (rt == void.class) return null;
            return null;
        };

        Object guardProxy = Proxy.newProxyInstance(cl, new Class[]{requestHandlerClass}, handler);

        Method addRequestHandler = clientClass.getMethod("addRequestHandler", requestHandlerClass, cefBrowserClass);
        addRequestHandler.invoke(client, guardProxy, cefBrowser);
    }
}
package dev.chronovault.intellij;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;

/**
 * Embedded CHRONOVAULT dashboard tool window.
 *
 * <p>Primary mode: JCEF loads {@code http://127.0.0.1:<port>/} served by the
 * local {@code chronovault ui} (same source of truth as the VS Code webview).
 * Falls back to a native panel with the action toolbar + a one-click
 * "Open in Browser" action when JCEF is unavailable or the server fails.
 */
public class ChronoVaultToolWindowFactory implements ToolWindowFactory {

    private static final Logger LOG = Logger.getInstance(ChronoVaultToolWindowFactory.class);

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        String cli = ChronoVaultAction.resolveCli();
        String projectRoot = project.getBasePath();
        toolWindow.setStripeTitle("ChronoVault");

        if (cli == null) {
            addNativeContent(project, toolWindow, projectRoot,
                "CHRONOVAULT runtime not found. Add the CLI to PATH or set CHRONOVAULT_CLI.",
                true);
            return;
        }

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(loadingLabel(), BorderLayout.CENTER);
        Content content = ContentFactory.getInstance().createContent(wrapper, "", false);
        toolWindow.getContentManager().addContent(content);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                DashboardServer server = DashboardServer.start(new DashboardServer.StartOptions()
                    .cli(cli)
                    .projectRoot(projectRoot)
                    .readyTimeoutSeconds(12));

                Disposer.register(project, () -> server.close());

                SwingUtilities.invokeLater(() -> {
                    if (toolWindow.isDisposed() || !toolWindow.isActive()) {
                        server.close();
                        return;
                    }
                    JComponent body = tryBrowserPanel(server.port(), project, toolWindow);
                    wrapper.removeAll();
                    wrapper.add(body, BorderLayout.CENTER);
                    wrapper.revalidate();
                    wrapper.repaint();
                });
            } catch (Exception ex) {
                LOG.info("CHRONOVAULT dashboard server could not be started", ex);
                SwingUtilities.invokeLater(() -> {
                    wrapper.removeAll();
                    addNativeContent(project, toolWindow, projectRoot,
                        "Could not start the CHRONOVAULT dashboard server: " + ex.getMessage(),
                        false);
                });
            }
        });
    }

    private static JComponent tryBrowserPanel(int port, Project project, ToolWindow toolWindow) {
        try {
            return jcefBrowserPanel(port, project, toolWindow);
        } catch (Throwable t) {
            LOG.info("JCEF unavailable, using native dashboard fallback", t);
            return nativeFallbackPanel(project, toolWindow,
                "The embedded dashboard requires a JCEF-capable IDE.",
                false);
        }
    }

    /** JCEF browser that loads the ChronoVault dashboard served on loopback. */
    private static JComponent jcefBrowserPanel(int port, Project project, ToolWindow toolWindow) throws Throwable {
        // Class loaded reflectively/conditionally so callers outside JCEF IDEs aren't broken.
        ClassLoader cl = ChronoVaultToolWindowFactory.class.getClassLoader();
        Class<?> browserClass = cl.loadClass("com.intellij.ui.jcef.JBCefBrowser");
        Object browser = browserClass.getConstructor().newInstance();
        java.lang.reflect.Method loadUrl = browserClass.getMethod("loadURL", String.class);
        loadUrl.invoke(browser, "http://127.0.0.1:" + port + "/");

        java.lang.reflect.Method getComponent = browserClass.getMethod("getComponent");
        JComponent component = (JComponent) getComponent.invoke(browser);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(component, BorderLayout.CENTER);
        panel.add(browserBar(project, toolWindow, port), BorderLayout.SOUTH);
        return panel;
    }

    /** Native panel used when JCEF is unavailable, runtime missing, or server fails. */
    private static JComponent nativeFallbackPanel(Project project, ToolWindow toolWindow,
                                                  String message, boolean showConfigureHint) {
        return nativeFallbackPanel(project, toolWindow, message, showConfigureHint, 0);
    }

    private static JComponent nativeFallbackPanel(Project project, ToolWindow toolWindow,
                                                  String message, boolean showConfigureHint,
                                                  int portOverride) {
        ActionToolbar toolbar = ActionManager.getInstance()
            .createActionToolbar("ChronoVault",
                (ActionGroup) ActionManager.getInstance().getAction("chronovault.actions"), false);
        toolbar.setTargetComponent(toolWindow.getComponent());

        JBLabel msgLabel = new JBLabel("<html><body style='margin:4px;'>" + escapeHtml(message) + "</body></html>");
        msgLabel.setFont(JBUI.Fonts.label());
        msgLabel.setForeground(JBColor.GRAY);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(JBUI.scale(10), JBUI.scale(10), JBUI.scale(10), JBUI.scale(10)));
        body.add(toolbar.getComponent());
        body.add(Box.createVerticalStrut(JBUI.scale(8)));
        body.add(msgLabel);

        if (portOverride > 0) {
            body.add(Box.createVerticalStrut(JBUI.scale(6)));
            body.add(browserLink("Open in Browser", project, portOverride));
        } else {
            body.add(Box.createVerticalStrut(JBUI.scale(6)));
            body.add(browserLink("Open dashboard in browser", project, 0));
        }

        body.add(Box.createVerticalGlue());
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(body, BorderLayout.NORTH);
        panel.setPreferredSize(new Dimension(JBUI.scale(320), JBUI.scale(460)));
        return panel;
    }

    private static void addNativeContent(Project project, ToolWindow toolWindow, String projectRoot,
                                         String message, boolean showConfigureHint) {
        JComponent panel = nativeFallbackPanel(project, toolWindow, message, showConfigureHint);
        Content content = ContentFactory.getInstance().createContent(new JBScrollPane(panel), "", false);
        toolWindow.getContentManager().addContent(content);
    }

    /** Minimal top bar for the JCEF panel with Tools + Open-in-browser. */
    private static JComponent browserBar(Project project, ToolWindow toolWindow, int port) {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()));
        bar.add(browserLink("Open in browser", project, port), BorderLayout.EAST);
        return bar;
    }

    private static JBLabel browserLink(String text, Project project, int port) {
        JBLabel link = new JBLabel(text);
        link.setForeground(JBColor.namedColor("link.foreground", JBColor.decode("#589df6")));
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setFont(link.getFont().deriveFont(Font.PLAIN));
        link.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(@NotNull MouseEvent e) {
                try {
                    if (port > 0) {
                        java.awt.Desktop.getDesktop().browse(new URI("http://127.0.0.1:" + port + "/"));
                    } else {
                        com.intellij.ide.BrowserUtil.browse("http://127.0.0.1:7723/");
                    }
                } catch (Exception ex) {
                    LOG.warn("CHRONOVAULT: failed to open browser", ex);
                }
            }
        });
        return link;
    }

    private static JBLabel loadingLabel() {
        JBLabel label = new JBLabel("Starting CHRONOVAULT dashboard\u2026", JBLabel.CENTER);
        label.setFont(JBUI.Fonts.label().deriveFont(Font.ITALIC));
        label.setForeground(JBColor.GRAY);
        return label;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
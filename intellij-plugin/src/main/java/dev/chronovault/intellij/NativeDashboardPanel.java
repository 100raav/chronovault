package dev.chronovault.intellij;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Functional native dashboard (used when JCEF is unavailable or fails): live
 * status banner, checkpoint timeline, and one-click Checkpoint / Health /
 * Diagnose / Restore actions. All network and JSON work happens off the EDT and
 * results are pushed back with {@link SwingUtilities#invokeLater}.
 */
public final class NativeDashboardPanel extends JPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(NativeDashboardPanel.class);
    private static final long POLL_INTERVAL_MS = 5000L;

    private final DashboardApiClient api;
    private final ScheduledExecutorService poller =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chronovault-native-panel");
            t.setDaemon(true);
            return t;
        });

    private final JBLabel banner = new JBLabel();
    private final DefaultListModel<DashboardModel.Row> listModel = new DefaultListModel<>();
    private final JBList<DashboardModel.Row> list = new JBList<>(listModel);
    private final JButton checkpointBtn = new JButton("Create Checkpoint");
    private final JButton healthBtn = new JButton("Verify Health");
    private final JButton diagnoseBtn = new JButton("What Broke It?");
    private final JButton restoreBtn = new JButton("Restore Last Good State");

    public NativeDashboardPanel(@NotNull DashboardApiClient api, @NotNull Disposable parent) {
        super(new BorderLayout());
        this.api = api;
        setPreferredSize(new Dimension(320, 480));
        add(buildHeader(), BorderLayout.NORTH);
        add(buildList(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        refreshNow();
        poller.scheduleWithFixedDelay(this::refreshNow, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        com.intellij.openapi.util.Disposer.register(parent, this);
    }

    // ---- construction ------------------------------------------------------

    private JComponent buildHeader() {
        banner.setFont(banner.getFont().deriveFont(Font.BOLD, 13f));
        banner.setForeground(JBColor.namedColor("List.foreground", JBColor.BLACK));
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        p.add(banner, BorderLayout.CENTER);
        JBLabel hint = new JBLabel("<html><small>auto-refreshing \u2022 loopback-only</small></html>");
        hint.setForeground(JBColor.GRAY);
        p.add(hint, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildList() {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                DashboardModel.Row row = (DashboardModel.Row) value;
                setText((row.status() == null || row.status().isBlank() ? "\u2022 " : row.status() + " \u2022 ")
                    + row.label() + (row.time().isEmpty() ? "" : "  \u2014  " + row.time()));
                return this;
            }
        });
        JBScrollPane scroll = new JBScrollPane(list);
        scroll.setBorder(BorderFactory.createTitledBorder("Checkpoints"));
        return scroll;
    }

    private JComponent buildFooter() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        for (JButton b : new JButton[]{checkpointBtn, healthBtn, diagnoseBtn, restoreBtn}) {
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            p.add(b);
            p.add(Box.createVerticalStrut(4));
        }
        checkpointBtn.addActionListener(e -> runAction("checkpoint", "POST /api/checkpoint", "Checkpoint created"));
        healthBtn.addActionListener(e -> runAction("health", "POST /api/health", "Health verification started"));
        diagnoseBtn.addActionListener(e -> runAction("diagnose", "GET /api/diagnose", "Diagnosis complete"));
        restoreBtn.addActionListener(e -> confirmRestore());
        return p;
    }

    // ---- polling (off EDT) -------------------------------------------------

    private void refreshNow() {
        try {
            String state = api.request("GET", "/api/state");
            String cps = api.request("GET", "/api/checkpoints");
            DashboardModel model = DashboardModel.parse(state, cps);
            SwingUtilities.invokeLater(() -> apply(model));
        } catch (Exception ex) {
            LOG.info("CHRONOVAULT native panel refresh failed", ex);
            SwingUtilities.invokeLater(() -> apply(DashboardModel.EMPTY));
        }
    }

    private void apply(DashboardModel model) {
        if (model == DashboardModel.EMPTY) {
            banner.setText("<html><b>CHRONOVAULT</b> \u2014 dashboard unreachable</html>");
            listModel.clear();
            return;
        }
        banner.setText("<html><b>CHRONOVAULT</b> \u2014 " + escape(model.status())
            + " · " + model.checkpoints() + " checkpoints · " + model.protectedCount() + " protected" + "<br><small>last verified: "
            + escape(model.lastVerified()) + "</small></html>");
        listModel.clear();
        for (DashboardModel.Row r : model.rows()) listModel.addElement(r);
    }

    private void confirmRestore() {
        int rc = Messages.showYesNoDialog("Restore the last verified checkpoint? Current work is protected first, and a failing verification rolls back automatically.",
            "CHRONOVAULT \u2014 Restore", Messages.getQuestionIcon());
        if (rc != Messages.YES) return;
        runAction("recover", "POST /api/recover", "Restore started");
    }

    /** Fire-and-forget action on the poller thread; show result at the end. */
    private void runAction(String name, String request, String doneText) {
        poller.execute(() -> {
            try {
                String body = api.request(request.split(" ")[0], request.split(" ")[1]);
                LOG.info("CHRONOVAULT " + name + ": " + body);
                SwingUtilities.invokeLater(() ->
                    Messages.showInfoMessage("CHRONOVAULT " + doneText + ".\n\n" + body, "CHRONOVAULT"));
            } catch (Exception ex) {
                LOG.error("CHRONOVAULT " + name + " failed", ex);
                SwingUtilities.invokeLater(() ->
                    Messages.showErrorDialog("CHRONOVAULT " + name + " failed:\n" + ex.getMessage(), "CHRONOVAULT"));
            }
        });
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void dispose() {
        poller.shutdownNow();
    }
}
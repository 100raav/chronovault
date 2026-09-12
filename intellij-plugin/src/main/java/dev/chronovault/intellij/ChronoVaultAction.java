package dev.chronovault.intellij;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Thin wrapper: runs the `chronovault` CLI in the current project root on a background thread. */
public class ChronoVaultAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(ChronoVaultAction.class);
    private static final int TIMEOUT_SECONDS = 600;
    private static final String GROUP_ID = "CHRONOVAULT";

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        String basePath = project == null ? System.getProperty("user.dir") : project.getBasePath();
        String cli = System.getenv().getOrDefault("CHRONOVAULT_CLI", "chronovault");

        String command = switch (e.getActionManager().getId(this)) {
            case "chronovault.diagnose" -> "diagnose";
            case "chronovault.restore" -> "restore --yes";
            case "chronovault.dashboard" -> "ui --open";
            default -> "checkpoint";
        };

        if ("ui --open".equals(command)) {
            launchDashboard(cli, basePath);
            return;
        }

        final String cmd = command;
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "CHRONOVAULT", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                String output = execute(cli, shortcutSplit(cmd), basePath, indicator);
                if (output != null && !output.isBlank()) {
                    Notification n = NotificationGroupManager.getInstance()
                        .getNotificationGroup(GROUP_ID)
                        .createNotification("CHRONOVAULT", output.trim(), NotificationType.INFORMATION);
                    Notifications.Bus.notify(n, project);
                }
            }
        });
    }

    private void launchDashboard(String cli, String projectRoot) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cli, "ui", "--open");
            if (projectRoot != null) pb.directory(new File(projectRoot));
            pb.environment().put("CHRONOVAULT_PROJECT", projectRoot);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
            Notifications.Bus.notify(NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification("CHRONOVAULT", "Dashboard launched.", NotificationType.INFORMATION), null);
        } catch (Exception ex) {
            LOG.warn("chronovault ui failed", ex);
            String msg = (ex instanceof java.io.IOException && System.getenv("CHRONOVAULT_CLI") == null)
                ? "The 'chronovault' CLI was not found on PATH (use CHRONOVAULT_CLI)."
                : "Dashboard launch failed: " + ex.getMessage();
            Notifications.Bus.notify(NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification("CHRONOVAULT", msg, NotificationType.ERROR), null);
        }
    }

    private String execute(String cli, List<String> args, String projectRoot, ProgressIndicator indicator) {
        List<String> cmd = new ArrayList<>();
        cmd.add(cli);
        cmd.addAll(args);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (projectRoot != null) pb.directory(new File(projectRoot));
            pb.environment().put("CHRONOVAULT_PROJECT", projectRoot);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                    if (indicator != null) indicator.checkCanceled();
                }
            } catch (ProcessCanceledException cancel) {
                p.destroyForcibly();
                return "Canceled.";
            }
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "chronovault " + args.get(0) + " timed out after " + TIMEOUT_SECONDS + "s.";
            }
            return out.toString();
        } catch (Exception ex) {
            if (ex instanceof java.io.IOException && System.getenv("CHRONOVAULT_CLI") == null) {
                return "The 'chronovault' CLI was not found on PATH (use CHRONOVAULT_CLI).";
            }
            LOG.warn("chronovault failed", ex);
            return "chronovault failed: " + ex.getMessage();
        }
    }

    private static List<String> shortcutSplit(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.split("\\s+")) if (!t.isBlank()) out.add(t);
        return out;
    }
}
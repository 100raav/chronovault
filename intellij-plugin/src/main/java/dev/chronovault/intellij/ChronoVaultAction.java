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
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Thin wrapper: runs the `chronovault` CLI in the current project root on a background thread. */
public class ChronoVaultAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(ChronoVaultAction.class);
    private static final int TIMEOUT_SECONDS = 600;
    private static final String GROUP_ID = "CHRONOVAULT";
    private static final String CLI_NAME = "chronovault";

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        String basePath = project == null ? System.getProperty("user.dir") : project.getBasePath();
        String actionId = e.getActionManager().getId(this);
        String cli = resolveCli();

        if ("chronovault.dashboard".equals(actionId)) {
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("ChronoVault").activate(null, false);
            return;
        }

        String command = switch (actionId) {
            case "chronovault.diagnose" -> "diagnose";
            case "chronovault.health" -> "health";
            case "chronovault.restore" -> "restore --yes";
            case "chronovault.dashboard.browser" -> "ui --open";
            default -> "checkpoint";
        };

        if ("restore --yes".equals(command)) {
            int answer = Messages.showYesNoDialog(project,
                "Restore the project to its last verified state?\n\n"
                    + "Current work is protected first, and CHRONOVAULT rolls back automatically "
                    + "if verification fails after the restore.",
                "ChronoVault Restore", Messages.getQuestionIcon());
            if (answer != Messages.YES) return;
        }

        if (cli == null) {
            String msg = "CHRONOVAULT runtime could not be located. Set the CHRONOVAULT_CLI environment "
                + "variable to the full path of the chronovault executable, or add it to PATH.";
            notifyError(msg, project);
            return;
        }

        if ("ui --open".equals(command)) {
            launchDashboard(cli, basePath, project);
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

    private void launchDashboard(String cli, String projectRoot, Project project) {
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
            notifyError("Dashboard launch failed: " + ex.getMessage(), project);
        }
    }

    private static void notifyError(String message, Project project) {
        Notifications.Bus.notify(NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification("CHRONOVAULT", message, NotificationType.ERROR), project);
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
            LOG.warn("chronovault failed", ex);
            return "chronovault failed: " + ex.getMessage();
        }
    }

    private static List<String> shortcutSplit(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.split("\\s+")) if (!t.isBlank()) out.add(t);
        return out;
    }

    /** Resolves the CLI: configured override, then PATH, then safe platform-specific locations. */
    @Nullable
    static String resolveCli() {
        String override = System.getenv("CHRONOVAULT_CLI");
        if (override != null && !override.isBlank()) {
            File f = new File(trimQuotes(override));
            if (isExecutable(f)) return f.getAbsolutePath();
        }

        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isEmpty()) continue;
                for (String variant : exeVariants(CLI_NAME)) {
                    File candidate = new File(dir, variant);
                    if (isExecutable(candidate)) return candidate.getAbsolutePath();
                }
            }
        }

        for (String dir : safeLocations()) {
            if (dir == null) continue;
            for (String variant : exeVariants(CLI_NAME)) {
                File candidate = new File(dir, variant);
                if (isExecutable(candidate)) return candidate.getAbsolutePath();
            }
        }
        return null;
    }

    private static String trimQuotes(String s) {
        String t = s;
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
        if (t.length() >= 2 && t.startsWith("'") && t.endsWith("'")) t = t.substring(1, t.length() - 1);
        return t;
    }

    private static List<String> exeVariants(String name) {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return Arrays.asList(name + ".exe", name + ".cmd", name + ".bat", name);
        }
        return List.of(name);
    }

    private static List<String> safeLocations() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String home = System.getProperty("user.home");
        List<String> dirs = new ArrayList<>();
        if (home != null && !home.isBlank()) {
            dirs.add(home + File.separator + ".chronovault" + File.separator + "bin");
            dirs.add(home + File.separator + ".local" + File.separator + "bin");
            if (!windows) dirs.add(home + File.separator + "bin");
        }
        if (windows) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null) dirs.add(localAppData + File.separator + "chronovault" + File.separator + "bin");
            String programFiles = System.getenv("ProgramFiles");
            if (programFiles != null) dirs.add(programFiles + File.separator + "Chronovault" + File.separator + "bin");
        } else {
            dirs.add("/usr/local/bin");
            if (System.getProperty("os.name", "").toLowerCase().contains("mac")) dirs.add("/opt/homebrew/bin");
            dirs.add("/opt/bin");
        }
        return dirs;
    }

    private static boolean isExecutable(File f) {
        try {
            if (f == null || !f.isFile() || !f.canRead()) return false;
            String os = System.getProperty("os.name", "").toLowerCase();
            return os.contains("win") || f.canExecute();
        } catch (SecurityException se) {
            return false;
        }
    }
}
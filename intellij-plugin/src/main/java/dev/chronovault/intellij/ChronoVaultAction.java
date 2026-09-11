package dev.chronovault.intellij;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Thin wrapper: runs the `chronovault` CLI in the current project root. */
public class ChronoVaultAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(ChronoVaultAction.class);
    private static final int TIMEOUT_SECONDS = 600;

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
        execute(cli, split(command), basePath);
    }

    private void execute(String cli, List<String> args, String projectRoot) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(cli);
            cmd.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (projectRoot != null) pb.directory(new java.io.File(projectRoot));
            pb.environment().put("CHRONOVAULT_PROJECT", projectRoot);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    p.getInputStream(), StandardCharsets.UTF_8))) {
                r.lines().limit(400).forEach(LOG::info);
            }
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                LOG.warn("chronovault " + args + " timed out");
            }
        } catch (Exception ex) {
            LOG.warn("chronovault failed", ex);
        }
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.split("\\s+")) if (!t.isBlank()) out.add(t);
        return out;
    }
}
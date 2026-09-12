package dev.chronovault.intellij;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;

/** Minimal native CHRONOVAULT tool window: one-click access to the existing actions. */
public class ChronoVaultToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ActionToolbar toolbar = ActionManager.getInstance()
            .createActionToolbar("ChronoVault", (ActionGroup) ActionManager.getInstance().getAction("chronovault.actions"), false);
        toolbar.setTargetComponent(toolWindow.getComponent());

        JBLabel hint = new JBLabel("Create verified checkpoints, diagnose breakage, and restore the last good state.");
        hint.setBorder(BorderFactory.createEmptyBorder(JBUI.scale(8), JBUI.scale(8), JBUI.scale(8), JBUI.scale(8)));

        JPanel actions = new JPanel();
        actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
        actions.setBorder(BorderFactory.createEmptyBorder(JBUI.scale(8), JBUI.scale(8), JBUI.scale(8), JBUI.scale(8)));
        actions.add(toolbar.getComponent());
        actions.add(Box.createVerticalGlue());

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(hint, BorderLayout.NORTH);
        panel.add(actions, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(JBUI.scale(280), JBUI.scale(420)));

        Content content = ContentFactory.getInstance().createContent(new JBScrollPane(panel), "", false);
        toolWindow.getContentManager().addContent(content);
    }
}
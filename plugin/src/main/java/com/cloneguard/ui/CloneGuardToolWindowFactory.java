package com.cloneguard.ui;

import com.cloneguard.model.CloneGroup;
import com.cloneguard.model.CloneType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.*;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.*;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/**
 * SCENARIO 2 — CloneGuard Tool Window
 *
 * Appears at the bottom of the IDE (like the Terminal tab).
 * Shows all clone groups found by the file scanner.
 * Each group has a [Refactor] button.
 */
public class CloneGuardToolWindowFactory implements ToolWindowFactory {

    private static ScanResultsPanel panel;

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        panel = new ScanResultsPanel(project);
        toolWindow.getContentManager().addContent(
                toolWindow.getContentManager().getFactory()
                        .createContent(panel.getRoot(), "Scan Results", false)
        );
    }

    /**
     * Called by ScanFileAction to populate the panel with results.
     */
    public static void showResults(Project project, List<CloneGroup> groups, String fileName) {
        // Ensure tool window is open
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("CloneGuard");
        if (toolWindow != null) {
            toolWindow.show();
            toolWindow.activate(null);
        }

        if (panel != null) {
            panel.displayResults(groups, fileName);
        }
    }
}

/**
 * The actual results panel UI.
 */
class ScanResultsPanel {

    private final Project   project;
    private final JPanel    root;
    private final JPanel    resultsContainer;
    private final JLabel    summaryLabel;

    ScanResultsPanel(Project project) {
        this.project = project;
        this.root    = new JPanel(new BorderLayout());

        // ── Top bar ──────────────────────────────────────────────────────────
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBorder(new EmptyBorder(8, 12, 8, 12));
        topBar.setBackground(JBColor.background());

        summaryLabel = new JLabel("Run 'Tools → CloneGuard → Scan Current File' or press Ctrl+Shift+G");
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.PLAIN, 12f));
        topBar.add(summaryLabel, BorderLayout.WEST);

        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> clearResults());
        topBar.add(clearBtn, BorderLayout.EAST);

        root.add(topBar, BorderLayout.NORTH);

        // ── Scrollable results area ───────────────────────────────────────────
        resultsContainer = new JPanel();
        resultsContainer.setLayout(new BoxLayout(resultsContainer, BoxLayout.Y_AXIS));
        resultsContainer.setBorder(new EmptyBorder(8, 12, 8, 12));

        JBScrollPane scroll = new JBScrollPane(resultsContainer);
        scroll.setBorder(null);
        root.add(scroll, BorderLayout.CENTER);
    }

    JPanel getRoot() { return root; }

    void displayResults(List<CloneGroup> groups, String fileName) {
        resultsContainer.removeAll();

        if (groups.isEmpty()) {
            summaryLabel.setText("✅ No clones found in " + fileName);
            JLabel empty = new JLabel("No clone groups detected. Your code is clean!");
            empty.setForeground(JBColor.GREEN.darker());
            empty.setBorder(new EmptyBorder(16, 0, 0, 0));
            resultsContainer.add(empty);
        } else {
            summaryLabel.setText("⚠️ " + groups.size() + " clone group(s) found in " + fileName);
            for (int i = 0; i < groups.size(); i++) {
                resultsContainer.add(buildGroupCard(groups.get(i), i + 1));
                resultsContainer.add(Box.createVerticalStrut(8));
            }
        }

        resultsContainer.revalidate();
        resultsContainer.repaint();
    }

    private JPanel buildGroupCard(CloneGroup group, int index) {
        JPanel card = new JPanel(new BorderLayout(8, 0));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(typeColor(group.cloneType), 2),
                new EmptyBorder(10, 12, 10, 12)
        ));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        card.setBackground(JBColor.background().brighter());

        // Left: info
        JPanel info = new JPanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.setOpaque(false);

        JLabel typeLabel = new JLabel("#" + index + "  " + group.cloneType.label);
        typeLabel.setFont(typeLabel.getFont().deriveFont(Font.BOLD, 13f));
        typeLabel.setForeground(typeColor(group.cloneType));

        JLabel methodsLabel = new JLabel("Functions: " + String.join("  ↔  ", group.methods));
        methodsLabel.setFont(methodsLabel.getFont().deriveFont(Font.PLAIN, 12f));

        JLabel simLabel = new JLabel(String.format("Similarity: %.0f%%  |  %s",
                group.similarity * 100, group.detail));
        simLabel.setFont(simLabel.getFont().deriveFont(Font.PLAIN, 11f));
        simLabel.setForeground(JBColor.GRAY);

        info.add(typeLabel);
        info.add(Box.createVerticalStrut(4));
        info.add(methodsLabel);
        info.add(Box.createVerticalStrut(2));
        info.add(simLabel);

        card.add(info, BorderLayout.CENTER);

        // Right: Refactor button
        JButton refactorBtn = new JButton("Refactor →");
        refactorBtn.setBackground(typeColor(group.cloneType));
        refactorBtn.setForeground(Color.WHITE);
        refactorBtn.setFocusPainted(false);
        refactorBtn.addActionListener(e -> triggerRefactor(group));
        card.add(refactorBtn, BorderLayout.EAST);

        return card;
    }

    private void triggerRefactor(CloneGroup group) {
        // Show refactor suggestion dialog
        String msg = "Refactoring suggestion for " + group.cloneType.label + ":\n\n" +
                "Functions involved: " + String.join(", ", group.methods) + "\n\n" +
                "Recommended action:\n" +
                refactorSuggestion(group.cloneType);

        JOptionPane.showMessageDialog(root, msg, "CloneGuard — Refactor Suggestion",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private String refactorSuggestion(CloneType type) {
        return switch (type) {
            case TYPE_1 -> "Remove the duplicate and replace all usages with a single shared function.";
            case TYPE_2 -> "Extract the common logic into a shared function. " +
                           "Rename parameters consistently.";
            case TYPE_3 -> "Extract the shared core into a base function. " +
                           "Pass the minor differences (null check, logging) as parameters or strategy objects.";
            case TYPE_4 -> "Consider whether both implementations are needed. " +
                           "If they serve the same purpose, consolidate into one canonical implementation.";
        };
    }

    private void clearResults() {
        resultsContainer.removeAll();
        summaryLabel.setText("Run 'Tools → CloneGuard → Scan Current File' or press Ctrl+Shift+G");
        resultsContainer.revalidate();
        resultsContainer.repaint();
    }

    private Color typeColor(CloneType type) {
        return switch (type) {
            case TYPE_1 -> new Color(220, 53,  69);
            case TYPE_2 -> new Color(255, 140,  0);
            case TYPE_3 -> new Color(204, 153,  0);
            case TYPE_4 -> new Color(108, 117, 125);
        };
    }
}

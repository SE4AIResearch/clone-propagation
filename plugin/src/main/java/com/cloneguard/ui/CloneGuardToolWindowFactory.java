package com.cloneguard.ui;

import com.cloneguard.model.CloneGroup;
import com.cloneguard.model.CloneType;
import com.cloneguard.services.FileScannerService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.*;
import com.intellij.psi.*;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.*;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * SCENARIO 2 — CloneGuard Tool Window
 *
 * Appears at the bottom of the IDE (like the Terminal tab).
 * Shows all clone groups found by the file scanner.
 * Each group has a [Refactor →] button that actually rewrites the code.
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

    public static void showResults(Project project, List<CloneGroup> groups, String fileName) {
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

class ScanResultsPanel {

    private final Project project;
    private final JPanel  root;
    private final JPanel  resultsContainer;
    private final JLabel  summaryLabel;
    private String        currentFileName = "";

    // Tracks every "Delegate →" button currently on screen. The moment ANY
    // one of them is clicked, all of them are disabled immediately — this
    // is what stops a user from clicking a second refactor button against
    // scan results that are already stale because the first click already
    // modified the file. Without this, clicking multiple refactor buttons
    // in a row (before a re-scan runs) was producing duplicate helper
    // methods and unreachable-code compile errors.
    private final List<JButton> activeRefactorButtons = new ArrayList<>();

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
        activeRefactorButtons.clear();
        currentFileName = fileName;

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

        // Right: Refactor button — label reflects which technique will be used
        String btnLabel = (group.cloneType == CloneType.TYPE_4) ? "Delegate →" : "Extract →";
        JButton refactorBtn = new JButton(btnLabel);
        refactorBtn.setBackground(typeColor(group.cloneType));
        refactorBtn.setForeground(Color.WHITE);
        refactorBtn.setFocusPainted(false);
        refactorBtn.addActionListener(e -> {
            // Disable every refactor button currently visible BEFORE doing
            // anything else. This closes the window where a user could
            // click a second group's button while this click's edit (and
            // the re-scan that follows it) hasn't finished yet.
            for (JButton btn : activeRefactorButtons) {
                btn.setEnabled(false);
            }
            triggerRefactor(group);
        });
        activeRefactorButtons.add(refactorBtn);
        card.add(refactorBtn, BorderLayout.EAST);

        return card;
    }

    // ── PHASE 1: Route refactoring by clone type ─────────────────────────────
    //
    // Actual extraction/delegation logic lives in ExtractMethodEngine —
    // shared with Scenario 1's gutter icon, so both entry points always run
    // through the exact same, extensively hand-tested implementation rather
    // than two separately-maintained copies. See ExtractMethodEngine for the
    // full list of safety measures.
    //
    // Type 4 semantic clones share no literal code by definition, so Extract
    // Method has nothing to work with there — routed to delegate() instead,
    // mirroring the same Extract-vs-Delegate split already built and
    // verified on the GitHub-bot side of CloneGuard (server.py).
    private void triggerRefactor(CloneGroup group) {
        if (group.methods.size() < 2) {
            JOptionPane.showMessageDialog(root,
                    "Need at least 2 functions to refactor.",
                    "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String canonical = group.methods.get(0);
        String duplicate = group.methods.get(1);

        if (group.cloneType == CloneType.TYPE_4) {
            com.cloneguard.refactor.ExtractMethodEngine.getInstance(project)
                    .delegate(canonical, duplicate, group.cloneType.label, this::rescanCurrentFile);
        } else {
            com.cloneguard.refactor.ExtractMethodEngine.getInstance(project)
                    .extract(canonical, duplicate, group.cloneType.label, this::rescanCurrentFile);
        }
    }


    // ── Re-scan the current file and refresh the tool window with fresh results ──
    private void rescanCurrentFile(PsiFile psiFile) {
        FileScannerService scanner = project.getService(FileScannerService.class);
        if (scanner == null) return;

        List<CloneGroup> freshGroups = ReadAction.compute(() -> scanner.scanFile(psiFile));
        ApplicationManager.getApplication().invokeLater(() ->
                CloneGuardToolWindowFactory.showResults(project, freshGroups, psiFile.getName())
        );
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
            case TYPE_2 -> new Color(255, 140,   0);
            case TYPE_3 -> new Color(204, 153,   0);
            case TYPE_4 -> new Color(108, 117, 125);
        };
    }
}
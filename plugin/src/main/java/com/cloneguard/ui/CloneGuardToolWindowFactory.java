package com.cloneguard.ui;

import com.cloneguard.model.CloneGroup;
import com.cloneguard.model.CloneType;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.*;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.*;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Arrays;
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
        // ── BUG 2 FIX: calls the real refactor method, not just a dialog ─────
        refactorBtn.addActionListener(e -> triggerRefactor(group));
        // ─────────────────────────────────────────────────────────────────────
        card.add(refactorBtn, BorderLayout.EAST);

        return card;
    }

    // ── BUG 2 FIX: actual PSI-based refactor ─────────────────────────────────
    private void triggerRefactor(CloneGroup group) {
        if (group.methods.size() < 2) {
            JOptionPane.showMessageDialog(root,
                    "Need at least 2 functions to refactor.",
                    "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String canonical  = group.methods.get(0);   // keep this one
        String duplicate  = group.methods.get(1);   // delete/replace this one

        // Step 1 — confirm with the developer before touching any code
        String message =
                "CloneGuard found a " + group.cloneType.label + ":\n\n" +
                "  Canonical:  " + canonical + "()\n" +
                "  Duplicate:  " + duplicate + "()\n\n" +
                "Proposed refactoring:\n" +
                "  • Delete the body of " + duplicate + "()\n" +
                "  • Replace it with a delegation call to " + canonical + "()\n\n" +
                refactorSuggestion(group.cloneType) + "\n\n" +
                "Apply this refactoring now?";

        int choice = JOptionPane.showConfirmDialog(
                root, message,
                "CloneGuard — Confirm Refactor",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );

        if (choice != JOptionPane.YES_OPTION) return;

        // Step 2 — find the current open file
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            JOptionPane.showMessageDialog(root,
                    "No file is open in the editor. Open the file containing these functions first.",
                    "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }

        PsiFile psiFile = PsiDocumentManager.getInstance(project)
                .getPsiFile(editor.getDocument());
        if (psiFile == null) {
            JOptionPane.showMessageDialog(root,
                    "Could not read the open file. Make sure it is saved.",
                    "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Step 3 — find the duplicate PsiMethod
        PsiMethod[] allMethods = PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod.class)
                .toArray(PsiMethod[]::new);

        PsiMethod canonicalMethod = null;
        PsiMethod duplicateMethod = null;

        for (PsiMethod m : allMethods) {
            if (m.getName().equals(canonical))  canonicalMethod = m;
            if (m.getName().equals(duplicate))  duplicateMethod = m;
        }

        if (duplicateMethod == null) {
            JOptionPane.showMessageDialog(root,
                    "Could not find method '" + duplicate + "' in the open file.\n" +
                    "Make sure the correct file is open in the editor.",
                    "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (canonicalMethod == null) {
            JOptionPane.showMessageDialog(root,
                    "Could not find canonical method '" + canonical + "' in the open file.",
                    "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Step 4 — build the delegation call body
        // Collect parameter names from the duplicate method's signature
        PsiParameter[] params = duplicateMethod.getParameterList().getParameters();
        String paramList = Arrays.stream(params)
                .map(PsiParameter::getName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        PsiType returnType = duplicateMethod.getReturnType();
        boolean returnsVoid = returnType == null ||
                returnType.equals(PsiType.VOID);

        String delegationBody = returnsVoid
                ? "{\n        " + canonical + "(" + paramList + ");\n    }"
                : "{\n        return " + canonical + "(" + paramList + ");\n    }";

        // Step 5 — write the change via PSI (undoable, appears in git diff)
        final PsiMethod finalDuplicate  = duplicateMethod;
        final String    finalBody       = delegationBody;

        WriteCommandAction.runWriteCommandAction(project, "CloneGuard Refactor", null, () -> {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

            // Replace the duplicate method's body only — keep its signature
            // so callers still compile (they'll now delegate to canonical)
            PsiCodeBlock newBody = factory.createCodeBlockFromText(finalBody, finalDuplicate);
            PsiCodeBlock oldBody = finalDuplicate.getBody();
            if (oldBody != null) {
                oldBody.replace(newBody);
            }

            // Reformat the changed method
            com.intellij.psi.codeStyle.CodeStyleManager
                    .getInstance(project)
                    .reformat(finalDuplicate);
        });

        // Step 6 — update the panel to show the refactor was applied
        JOptionPane.showMessageDialog(root,
                "✅ Refactoring applied!\n\n" +
                duplicate + "() now delegates to " + canonical + "().\n\n" +
                "Next steps:\n" +
                "1. Run your tests to confirm no regressions.\n" +
                "2. Re-scan the file (Ctrl+Shift+G) to confirm zero clones.\n" +
                "3. Commit and push — the PR bot will re-check automatically.",
                "CloneGuard — Refactor Complete",
                JOptionPane.INFORMATION_MESSAGE);
    }
    // ─────────────────────────────────────────────────────────────────────────

    private String refactorSuggestion(CloneType type) {
        return switch (type) {
            case TYPE_1 -> "Exact duplicate — safe to replace body with delegation call immediately.";
            case TYPE_2 -> "Same structure, renamed identifiers — delegation call will unify them.";
            case TYPE_3 -> "Near-miss clone — delegation handles the shared core. " +
                           "Review any minor differences (null checks, logging) manually after.";
            case TYPE_4 -> "Semantic clone — same intent, different implementation. " +
                           "Delegation preserves the API while consolidating the logic.";
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
            case TYPE_2 -> new Color(255, 140,   0);
            case TYPE_3 -> new Color(204, 153,   0);
            case TYPE_4 -> new Color(108, 117, 125);
        };
    }
}
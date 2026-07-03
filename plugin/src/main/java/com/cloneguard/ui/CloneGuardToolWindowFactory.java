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

        // Right: Refactor button — label reflects which technique will be used
        String btnLabel = switch (group.cloneType) {
            case TYPE_1, TYPE_2, TYPE_3 -> "Delegate →";
            case TYPE_4                 -> "Delegate →";
        };
        JButton refactorBtn = new JButton(btnLabel);
        refactorBtn.setBackground(typeColor(group.cloneType));
        refactorBtn.setForeground(Color.WHITE);
        refactorBtn.setFocusPainted(false);
        refactorBtn.addActionListener(e -> triggerRefactor(group));
        card.add(refactorBtn, BorderLayout.EAST);

        return card;
    }

    // ── PHASE 1: Route refactoring by clone type ─────────────────────────────
    private void triggerRefactor(CloneGroup group) {
        if (group.methods.size() < 2) {
            JOptionPane.showMessageDialog(root,
                    "Need at least 2 functions to refactor.",
                    "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }

        switch (group.cloneType) {
            case TYPE_1:
            case TYPE_2:
                // Refactor Layer 1 — Method Delegation
                // Bodies are identical or structurally identical —
                // safe to automate, no semantic understanding needed
                triggerMethodDelegation(group);
                break;

            case TYPE_3:
                // Refactor Layer 2 — Method Delegation
                // Extract Method is the theoretically correct technique for Type 3,
                // but requires full AST-level rewriting to handle renamed variables
                // correctly across both method bodies and the extracted helper.
                // Method Delegation achieves the same goal (eliminating duplication)
                // in one safe step: B delegates to A, logic lives in one place.
                // Extract Method is documented as a planned Phase 2 improvement.
                triggerMethodDelegation(group);
                break;

            case TYPE_4:
                // Refactor Layer 1 — Method Delegation + Suggestion
                // Implementations are completely different (no common code to extract)
                // so delegation is the only safe automated option.
                // Show a suggestion afterward recommending which implementation to keep.
                triggerMethodDelegation(group);
                showType4Suggestion(group);
                break;
        }
    }

    // ── REFACTOR LAYER 1: Method Delegation (Type 1, 2, 4) ───────────────────
    private void triggerMethodDelegation(CloneGroup group) {
        String canonical = group.methods.get(0);
        String duplicate = group.methods.get(1);

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

        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            JOptionPane.showMessageDialog(root,
                    "No file is open in the editor.",
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

        PsiMethod canonicalMethod = null;
        PsiMethod duplicateMethod = null;
        for (PsiMethod m : PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod.class)) {
            if (m.getName().equals(canonical)) canonicalMethod = m;
            if (m.getName().equals(duplicate)) duplicateMethod = m;
        }

        if (duplicateMethod == null || canonicalMethod == null) {
            JOptionPane.showMessageDialog(root,
                    "Could not find one or both methods in the open file.",
                    "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }

        PsiParameter[] params = duplicateMethod.getParameterList().getParameters();
        String paramList = Arrays.stream(params)
                .map(PsiParameter::getName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        PsiType returnType = duplicateMethod.getReturnType();
        boolean returnsVoid = returnType == null || returnType.equals(PsiType.VOID);

        String delegationBody = returnsVoid
                ? "{\n        " + canonical + "(" + paramList + ");\n    }"
                : "{\n        return " + canonical + "(" + paramList + ");\n    }";

        final PsiMethod finalDuplicate = duplicateMethod;
        final String finalBody = delegationBody;

        WriteCommandAction.runWriteCommandAction(project, "CloneGuard Delegate", null, () -> {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
            PsiCodeBlock newBody = factory.createCodeBlockFromText(finalBody, finalDuplicate);
            PsiCodeBlock oldBody = finalDuplicate.getBody();
            if (oldBody != null) oldBody.replace(newBody);
            com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project).reformat(finalDuplicate);
        });

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

    // ── TYPE 3 CHOICE: Offer Extract Method or Delegation ────────────────────
    private void offerType3RefactoringChoice(CloneGroup group) {
        String methodA = group.methods.get(0);
        String methodB = group.methods.get(1);

        Object[] options = {"Extract Method", "Method Delegation", "Cancel"};
        int choice = JOptionPane.showOptionDialog(
                root,
                "CloneGuard found a " + group.cloneType.label + ":\n\n" +
                "  Method A:  " + methodA + "()\n" +
                "  Method B:  " + methodB + "()\n\n" +
                "Choose refactoring strategy:\n\n" +
                "📤 Extract Method (Refactor Layer 2)\n" +
                "   Extracts shared logic into a private helper.\n" +
                "   " + methodA + "() calls helper + keeps unique parts.\n" +
                "   " + methodB + "() delegates to " + methodA + "().\n" +
                "   Best when methods have meaningful unique surrounding code.\n\n" +
                "🔀 Method Delegation (Refactor Layer 1)\n" +
                "   Makes " + methodB + "() delegate directly to " + methodA + "().\n" +
                "   Simpler — guaranteed 0 clones in one step.\n" +
                "   Best when the shared logic dominates the method body.",
                "CloneGuard — Choose Refactoring Strategy",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );

        if (choice == 0) {
            triggerExtractMethod(group);
        } else if (choice == 1) {
            triggerMethodDelegation(group);
        }
        // choice == 2 or closed = Cancel, do nothing
    }

    // ── REFACTOR LAYER 2: Extract Method (Type 3) ────────────────────────────
    private void triggerExtractMethod(CloneGroup group) {
        String methodA = group.methods.get(0);  // canonical
        String methodB = group.methods.get(1);  // duplicate

        int choice = JOptionPane.showConfirmDialog(
                root,
                "CloneGuard found a " + group.cloneType.label + ":\n\n" +
                "  Method A:  " + methodA + "()\n" +
                "  Method B:  " + methodB + "()\n\n" +
                "Proposed refactoring (Extract Method):\n" +
                "  • Extract the shared core logic from " + methodA + "() into a private helper\n" +
                "  • " + methodA + "() calls the helper (preserving its unique parts)\n" +
                "  • " + methodB + "() delegates directly to " + methodA + "()\n\n" +
                "Result: 0 clone groups after refactoring.\n\n" +
                "Apply this refactoring now?",
                "CloneGuard — Confirm Extract Method",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        if (choice != JOptionPane.YES_OPTION) return;

        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            JOptionPane.showMessageDialog(root,
                    "No file is open in the editor.",
                    "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }

        PsiFile psiFile = PsiDocumentManager.getInstance(project)
                .getPsiFile(editor.getDocument());
        if (psiFile == null) {
            JOptionPane.showMessageDialog(root,
                    "Could not read the open file.",
                    "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Find both PSI methods
        PsiMethod psiMethodA = null;
        PsiMethod psiMethodB = null;
        for (PsiMethod m : PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod.class)) {
            if (m.getName().equals(methodA)) psiMethodA = m;
            if (m.getName().equals(methodB)) psiMethodB = m;
        }

        if (psiMethodA == null || psiMethodB == null) {
            JOptionPane.showMessageDialog(root,
                    "Could not find one or both methods in the open file.",
                    "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // ── Strategy: ─────────────────────────────────────────────────────────
        // Rather than making both methods call a helper (which causes them to
        // become structural clones of each other), we:
        //
        // 1. Find the common statements between the two methods
        // 2. Extract them into a private helper using method A's parameter names
        // 3. Rewrite method A to call the helper (keeping A's unique statements)
        // 4. Make method B directly DELEGATE to method A
        //    (using B's own parameter names so the call compiles correctly)
        //
        // This gives exactly 0 clone groups after one refactoring step.
        // ─────────────────────────────────────────────────────────────────────

        PsiStatement[] statementsA = psiMethodA.getBody() != null
                ? psiMethodA.getBody().getStatements() : new PsiStatement[0];
        PsiStatement[] statementsB = psiMethodB.getBody() != null
                ? psiMethodB.getBody().getStatements() : new PsiStatement[0];

        List<PsiStatement> commonStatements = findCommonStatements(statementsA, statementsB);

        // Build helper name
        String helperName = "core" +
                Character.toUpperCase(methodA.charAt(0)) + methodA.substring(1);

        // Use method A's parameters for the helper signature
        PsiParameter[] paramsA = psiMethodA.getParameterList().getParameters();
        String paramDeclarations = Arrays.stream(paramsA)
                .map(p -> p.getType().getPresentableText() + " " + p.getName())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        String paramNamesA = Arrays.stream(paramsA)
                .map(PsiParameter::getName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        // Use method B's parameters for its delegation call to method A
        PsiParameter[] paramsB = psiMethodB.getParameterList().getParameters();
        String paramNamesB = Arrays.stream(paramsB)
                .map(PsiParameter::getName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        PsiType returnType = psiMethodA.getReturnType();
        boolean returnsVoid = returnType == null || returnType.equals(PsiType.VOID);
        String returnTypeStr = returnsVoid ? "void" : returnType.getPresentableText();

        // Build helper body from common statements (using method A's variable names)
        StringBuilder helperBodySb = new StringBuilder("{\n");
        if (!commonStatements.isEmpty()) {
            for (PsiStatement s : commonStatements) {
                helperBodySb.append("        ").append(s.getText()).append("\n");
            }
        } else {
            // No common statements found — helper gets the full body of method A
            for (PsiStatement s : statementsA) {
                helperBodySb.append("        ").append(s.getText()).append("\n");
            }
        }
        helperBodySb.append("    }");

        String helperMethodText = "private " + returnTypeStr + " " + helperName
                + "(" + paramDeclarations + ") " + helperBodySb;

        // Method A's new body — calls helper, keeps unique statements
        // Find unique statements in A (those NOT in common)
        List<String> commonTextsNormalized = commonStatements.stream()
                .map(s -> s.getText().replaceAll("\\s+", " ").trim())
                .toList();

        StringBuilder newBodyA = new StringBuilder("{\n");
        boolean helperCallAdded = false;
        for (PsiStatement s : statementsA) {
            String normalized = s.getText().replaceAll("\\s+", " ").trim();
            if (commonTextsNormalized.contains(normalized) && !helperCallAdded) {
                // Replace the first occurrence of the common block with helper call
                if (returnsVoid) {
                    newBodyA.append("        ").append(helperName)
                            .append("(").append(paramNamesA).append(");\n");
                } else {
                    newBodyA.append("        int result = ").append(helperName)
                            .append("(").append(paramNamesA).append(");\n");
                }
                helperCallAdded = true;
            } else if (!commonTextsNormalized.contains(normalized)) {
                // Keep unique statements — replace result references if needed
                String stmtText = s.getText();
                if (!returnsVoid && helperCallAdded && stmtText.contains("return")) {
                    newBodyA.append("        return result;\n");
                } else {
                    newBodyA.append("        ").append(stmtText).append("\n");
                }
            }
        }
        if (!helperCallAdded) {
            // fallback — just call helper and return
            if (returnsVoid) {
                newBodyA.append("        ").append(helperName)
                        .append("(").append(paramNamesA).append(");\n");
            } else {
                newBodyA.append("        return ").append(helperName)
                        .append("(").append(paramNamesA).append(");\n");
            }
        }
        newBodyA.append("    }");

        // Method B's new body — directly delegates to method A
        // This is the key fix: B delegates to A (not to helper), so they
        // have completely different bodies and 0 clone groups result
        String newBodyB = returnsVoid
                ? "{\n        " + methodA + "(" + paramNamesB + ");\n    }"
                : "{\n        return " + methodA + "(" + paramNamesB + ");\n    }";

        final PsiMethod finalMethodA = psiMethodA;
        final PsiMethod finalMethodB = psiMethodB;
        final String    finalNewBodyA = newBodyA.toString();
        final String    finalNewBodyB = newBodyB;
        final String    finalHelper   = helperMethodText;

        WriteCommandAction.runWriteCommandAction(project, "CloneGuard Extract Method", null, () -> {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
            com.intellij.psi.codeStyle.CodeStyleManager cm =
                    com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project);

            // 1 — Add the helper method to the class
            PsiClass containingClass = (PsiClass) finalMethodA.getParent();
            PsiMethod helperMethod = factory.createMethodFromText(finalHelper, containingClass);
            containingClass.add(helperMethod);

            // 2 — Rewrite method A to call helper + keep unique parts
            PsiCodeBlock newBlockA = factory.createCodeBlockFromText(finalNewBodyA, finalMethodA);
            PsiCodeBlock oldBlockA = finalMethodA.getBody();
            if (oldBlockA != null) oldBlockA.replace(newBlockA);
            cm.reformat(finalMethodA);

            // 3 — Make method B delegate directly to method A
            PsiCodeBlock newBlockB = factory.createCodeBlockFromText(finalNewBodyB, finalMethodB);
            PsiCodeBlock oldBlockB = finalMethodB.getBody();
            if (oldBlockB != null) oldBlockB.replace(newBlockB);
            cm.reformat(finalMethodB);
        });

        JOptionPane.showMessageDialog(root,
                "✅ Extract Method applied!\n\n" +
                "• Helper created: " + helperName + "() — contains shared core logic\n" +
                "• " + methodA + "() — calls " + helperName + "() + keeps unique parts\n" +
                "• " + methodB + "() — delegates directly to " + methodA + "()\n\n" +
                "Re-scan now (Ctrl+Shift+G) — should show 0 clone groups.",
                "CloneGuard — Extract Method Complete",
                JOptionPane.INFORMATION_MESSAGE);
    }

    // ── Helper: find common statements between two method bodies ─────────────
    private List<PsiStatement> findCommonStatements(
            PsiStatement[] statementsA, PsiStatement[] statementsB) {

        // Normalize identifiers before comparing so renamed variables
        // (e.g. arr vs nums, i vs j, result vs count) don't prevent matching.
        // This is the same normalization LocalCloneDetector uses for Type 2 detection:
        // all non-keyword identifiers become VAR or FUNC placeholders,
        // so structurally identical statements match even when variable names differ.
        List<String> textsA = Arrays.stream(statementsA)
                .map(s -> normalizeForLCS(s.getText()))
                .toList();
        List<String> textsB = Arrays.stream(statementsB)
                .map(s -> normalizeForLCS(s.getText()))
                .toList();

        // Find longest common subsequence of normalized statement texts
        int m = textsA.size(), n = textsB.size();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (textsA.get(i - 1).equals(textsB.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        // Backtrack to find the actual common statements
        // Return statements from method A (canonical) for use in helper body
        List<PsiStatement> common = new java.util.ArrayList<>();
        int i = m, j = n;
        while (i > 0 && j > 0) {
            if (textsA.get(i - 1).equals(textsB.get(j - 1))) {
                common.add(0, statementsA[i - 1]);
                i--; j--;
            } else if (dp[i - 1][j] > dp[i][j - 1]) {
                i--;
            } else {
                j--;
            }
        }

        return common;
    }

    // ── Normalize identifiers for LCS comparison ─────────────────────────────
    // Replaces all non-keyword variable/method names with VAR/FUNC placeholders
    // so that structurally identical statements match even when variable names differ.
    private static final java.util.Set<String> JAVA_KEYWORDS = java.util.Set.of(
        "abstract","assert","boolean","break","byte","case","catch","char","class",
        "const","continue","default","do","double","else","enum","extends","final",
        "finally","float","for","goto","if","implements","import","instanceof","int",
        "interface","long","native","new","package","private","protected","public",
        "return","short","static","strictfp","super","switch","synchronized","this",
        "throw","throws","transient","try","void","volatile","while","true","false",
        "null","String","List","Map","Set","ArrayList","HashMap","Integer","Double",
        "Boolean","Object","Arrays","System","Math"
    );

    private static String normalizeForLCS(String code) {
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[A-Za-z_][A-Za-z0-9_]*|[^A-Za-z0-9_\\s]+|\\s+")
                .matcher(code);
        while (m.find()) {
            String token = m.group();
            if (token.matches("\\s+")) {
                sb.append(" ");
            } else if (JAVA_KEYWORDS.contains(token)) {
                sb.append(token);
            } else if (token.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                // peek ahead to see if followed by '(' → method call
                int end = m.end();
                String rest = code.substring(end).stripLeading();
                sb.append(rest.startsWith("(") ? "FUNC" : "VAR");
            } else {
                sb.append(token);
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    // ── PHASE 3: Type 4 Suggestion ────────────────────────────────────────────
    private void showType4Suggestion(CloneGroup group) {
        String canonical = group.methods.get(0);
        String duplicate = group.methods.get(1);

        JOptionPane.showMessageDialog(root,
                "💡 Type 4 — Semantic Clone: Further Recommendation\n\n" +
                "Method Delegation has been applied:\n" +
                "  " + duplicate + "() now delegates to " + canonical + "()\n\n" +
                "Since this is a Semantic Clone (same intent, different implementation),\n" +
                "consider the following:\n\n" +
                "• Review both implementations and decide which is superior\n" +
                "• Prefer iterative over recursive for large inputs\n" +
                "  (avoids stack overflow risk)\n" +
                "• Prefer stream-based for readability when performance is not critical\n" +
                "• If " + duplicate + "() was actually the better implementation,\n" +
                "  reverse the canonical and re-apply delegation\n\n" +
                "The delegation is safe either way — logic now lives in one place.",
                "CloneGuard — Type 4 Suggestion",
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
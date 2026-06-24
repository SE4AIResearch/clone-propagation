package com.cloneguard.ui;

import com.cloneguard.model.CloneResult;
import com.cloneguard.model.CloneType;
import com.cloneguard.services.FileScannerService;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;

public class CloneWarningDialog extends DialogWrapper {

    private final CloneResult result;
    private final String      ghostText;
    private final Editor      editor;

    public static final int EXIT_USE_EXISTING  = 10;
    public static final int EXIT_ACCEPT_ANYWAY = 11;

    public CloneWarningDialog(Project project, CloneResult result, String ghostText, Editor editor) {
        super(project, true);
        this.result    = result;
        this.ghostText = ghostText;
        this.editor    = editor;
        setTitle("⚠️ CloneGuard — Detection Warning");
        setModal(true);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setPreferredSize(new Dimension(600, 460));
        root.setBorder(new EmptyBorder(16, 16, 8, 16));
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);
        return root;
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));

        // AI badge — always shown
        JLabel aiBadge = new JLabel(result.isAiGenerated ? " 🤖 AI Generated " : " 👤 Human Written ");
        aiBadge.setOpaque(true);
        aiBadge.setBackground(result.isAiGenerated ? new Color(102, 51, 153) : new Color(40, 167, 69));
        aiBadge.setForeground(Color.WHITE);
        aiBadge.setFont(aiBadge.getFont().deriveFont(Font.BOLD, 12f));
        aiBadge.setBorder(JBUI.Borders.empty(4, 10));
        header.add(aiBadge);

        // Clone badge — only if clone detected
        if (result.isClone) {
            JLabel cloneBadge = new JLabel(" " + result.cloneType.label + " ");
            cloneBadge.setOpaque(true);
            cloneBadge.setBackground(badgeColor(result.cloneType));
            cloneBadge.setForeground(Color.WHITE);
            cloneBadge.setFont(cloneBadge.getFont().deriveFont(Font.BOLD, 12f));
            cloneBadge.setBorder(JBUI.Borders.empty(4, 10));
            header.add(cloneBadge);
        }

        JLabel title = new JLabel(buildTitleText());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        header.add(title);

        return header;
    }

    private String buildTitleText() {
        if (result.isAiGenerated && result.isClone) {
            return "AI-generated code that duplicates an existing function";
        } else if (result.isAiGenerated) {
            return "Pasted code appears to be AI-generated";
        } else {
            return "Pasted code matches an existing function";
        }
    }

    private JPanel buildBody() {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        // ── AI Detection Section ───────────────────────────────────────────
        JLabel aiHeader = new JLabel("── AI Detection ──────────────────────");
        aiHeader.setFont(aiHeader.getFont().deriveFont(Font.BOLD, 11f));
        aiHeader.setForeground(JBColor.GRAY);
        aiHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(aiHeader);
        body.add(Box.createVerticalStrut(4));

        body.add(infoRow("AI Generated:",    result.isAiGenerated ? "Yes" : "No"));
        body.add(infoRow("AI Confidence:",   String.format("%.0f%%", result.aiConfidence * 100)));
        body.add(infoRow("Assessment:",      result.aiLabel));
        body.add(Box.createVerticalStrut(10));

        // ── Clone Detection Section (only if clone found) ──────────────────
        if (result.isClone) {
            JLabel cloneHeader = new JLabel("── Clone Detection ───────────────────");
            cloneHeader.setFont(cloneHeader.getFont().deriveFont(Font.BOLD, 11f));
            cloneHeader.setForeground(JBColor.GRAY);
            cloneHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(cloneHeader);
            body.add(Box.createVerticalStrut(4));

            body.add(infoRow("Clone Type:",       result.cloneType.label));
            body.add(infoRow("Description:",      result.cloneType.description));
            body.add(infoRow("Similarity:",       String.format("%.0f%%", result.similarity * 100)));
            body.add(infoRow("Matched Function:", result.matchedFunction));
            body.add(infoRow("Detection Layer:",  result.layer));
            body.add(Box.createVerticalStrut(10));
        }

        // ── Code Preview ───────────────────────────────────────────────────
        JLabel previewLabel = new JLabel("Pasted Code:");
        previewLabel.setFont(previewLabel.getFont().deriveFont(Font.BOLD));
        previewLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(previewLabel);
        body.add(Box.createVerticalStrut(4));

        JTextArea codeArea = new JTextArea(ghostText);
        codeArea.setEditable(false);
        codeArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        codeArea.setBackground(JBColor.background().brighter());
        codeArea.setRows(6);

        JBScrollPane scroll = new JBScrollPane(codeArea);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));
        body.add(scroll);

        return body;
    }

    private JPanel infoRow(String label, String value) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JLabel lbl = new JLabel(label);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
        row.add(lbl);
        row.add(new JLabel(value));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    @Override
    protected Action @NotNull [] createActions() {
        if (result.isClone) {
            return new Action[]{ useExistingAction(), acceptAnywayAction(), getCancelAction() };
        } else {
            // AI detected but no clone — just accept or dismiss
            return new Action[]{ acceptAnywayAction(), getCancelAction() };
        }
    }

    @Override
    protected @NotNull Action getCancelAction() {
        Action cancel = super.getCancelAction();
        cancel.putValue(Action.NAME, "Dismiss");
        return cancel;
    }

    private Action useExistingAction() {
        return new AbstractAction("Use Existing Function") {
            @Override
            public void actionPerformed(ActionEvent e) {
                close(EXIT_USE_EXISTING);
                navigateToExistingFunction();
            }
        };
    }

    private Action acceptAnywayAction() {
        return new AbstractAction("Accept Anyway") {
            @Override
            public void actionPerformed(ActionEvent e) {
                close(EXIT_ACCEPT_ANYWAY);
                acceptSuggestion();
            }
        };
    }

    private void navigateToExistingFunction() {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            Project project = editor.getProject();
            if (project == null) return;
            FileScannerService scanner = project.getService(FileScannerService.class);
            if (scanner != null) scanner.navigateTo(result.matchedFunction, editor);
        });
    }

    private void acceptSuggestion() {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            Component comp = editor.getContentComponent();
            comp.dispatchEvent(new java.awt.event.KeyEvent(
                    comp, java.awt.event.KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(), 0,
                    java.awt.event.KeyEvent.VK_TAB, '\t'));
        });
    }

    private Color badgeColor(CloneType type) {
        return switch (type) {
            case TYPE_1 -> new Color(220, 53,  69);
            case TYPE_2 -> new Color(255, 140,  0);
            case TYPE_3 -> new Color(204, 153,  0);
            case TYPE_4 -> new Color(108, 117, 125);
        };
    }
}

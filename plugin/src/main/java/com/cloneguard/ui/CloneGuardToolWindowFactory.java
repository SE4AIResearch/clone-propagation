package com.cloneguard.ui;

import com.cloneguard.model.CloneGroup;
import com.cloneguard.model.CloneType;
import com.cloneguard.model.PushDownCandidate;
import com.cloneguard.services.FileScannerService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
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
 * Shows all clone groups found by the file scanner, PLUS any Push Down
 * Method candidates found by the same scan (see FileScannerService.
 * findPushDownCandidates() — a separate, independent analysis from clone
 * detection, since Push Down isn't about two duplicated methods, it's
 * about one method declared too high in a class hierarchy).
 *
 * Each clone group has a [Refactor →] button that actually rewrites the
 * code — that button now auto-routes to Pull Up Method first when the pair
 * qualifies (see triggerRefactor() below), falling back to Extract Method
 * or Method Delegation otherwise. Each push-down candidate has its own
 * [Push Down →] button.
 */
public class CloneGuardToolWindowFactory implements ToolWindowFactory {

    private static ScanResultsPanel panel;
    private static TrendDashboardPanel trendDashboardPanel;

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        panel = new ScanResultsPanel(project);
        toolWindow.getContentManager().addContent(
                toolWindow.getContentManager().getFactory()
                        .createContent(panel.getRoot(), "Scan Results", false)
        );

        // Trend Dashboard — a second tab in the same tool window, showing
        // the before/after quality trend tracked by MetricsTrackerService.
        // Loads once here at tool window creation, then again via its own
        // Refresh button, or automatically right after every scan (see
        // refreshTrendDashboard() below, called from ScanFileAction).
        trendDashboardPanel = new TrendDashboardPanel(project);
        toolWindow.getContentManager().addContent(
                toolWindow.getContentManager().getFactory()
                        .createContent(trendDashboardPanel.getRoot(), "Trend Dashboard", false)
        );
    }

    /**
     * Called from ScanFileAction right after every scan attempt — a new
     * scan starting is exactly the moment MetricsTrackerService finalizes
     * and writes the PREVIOUS session, so that's the only point new trend
     * data can actually exist. Takes the scanned file's name so the
     * dashboard can filter to THAT file's history specifically — see
     * TrendDashboardPanel.setCurrentFile().
     *
     * FIX (found live, per-file dashboard testing): this used to only
     * update the panel's internal data via setCurrentFile() -- it never
     * actually made the CloneGuard tool window itself visible or
     * focused, unlike showResults() below, which explicitly calls
     * toolWindow.show() + activate(). That meant the "need at least 2
     * functions" early-return path in ScanFileAction (the only caller
     * that reaches this method WITHOUT also calling showResults()
     * afterward) could correctly update the dashboard's data behind the
     * scenes, with the user having no visible indication it happened at
     * all if the tool window was closed or unfocused at the time.
     * Confirmed live: "the panel doesn't show up" after a rescan that
     * hit that guard. Now mirrors showResults()'s own show+activate
     * calls, so every path that refreshes the dashboard also guarantees
     * the user can actually see it.
     */
    public static void refreshTrendDashboard(Project project, String fileName) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("CloneGuard");
        if (toolWindow != null) {
            toolWindow.show();
            toolWindow.activate(null);
        }
        if (trendDashboardPanel != null) {
            trendDashboardPanel.setCurrentFile(fileName);
        }
    }

    /**
     * NEW (project-wide scan support): counterpart to
     * refreshTrendDashboard() above, called by ScanProjectAction once a
     * whole-project scan finishes -- shows the tool window and switches
     * the Trend Dashboard straight to its "Whole Project (Average)" view
     * rather than a specific file's trend, since that's the view a
     * project-wide scan's results actually belong in.
     */
    public static void refreshTrendDashboardProjectAverage(Project project) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("CloneGuard");
        if (toolWindow != null) {
            toolWindow.show();
            toolWindow.activate(null);
        }
        if (trendDashboardPanel != null) {
            trendDashboardPanel.reloadProjectAverage();
        }
    }

    public static void showResults(Project project, List<CloneGroup> groups, List<PushDownCandidate> pushDownCandidates, String fileName) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("CloneGuard");
        if (toolWindow != null) {
            toolWindow.show();
            toolWindow.activate(null);
        }
        if (panel != null) {
            panel.displayResults(groups, pushDownCandidates, fileName);
        }
    }
}

class ScanResultsPanel {

    private final Project project;
    private final JPanel  root;
    private final JPanel  resultsContainer;
    private final JLabel  summaryLabel;
    private String        currentFileName = "";

    // Tracks every refactor-triggering button currently on screen — both
    // clone-group "Extract/Delegate →" buttons AND push-down candidate
    // "Push Down →" buttons share this same list. The moment ANY one of
    // them is clicked, ALL of them are disabled immediately — this is what
    // stops a user from clicking a second refactor button against scan
    // results that are already stale because the first click already
    // modified the file. Without this, clicking multiple refactor buttons
    // in a row (before a re-scan runs) was producing duplicate helper
    // methods and unreachable-code compile errors. Push Down candidates
    // are just as vulnerable to this same staleness problem as clone
    // groups are, so they're tracked in the same list rather than a
    // separate one.
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

    void displayResults(List<CloneGroup> groups, List<PushDownCandidate> pushDownCandidates, String fileName) {
        resultsContainer.removeAll();
        activeRefactorButtons.clear();
        currentFileName = fileName;

        boolean hasGroups = groups != null && !groups.isEmpty();
        boolean hasPushDown = pushDownCandidates != null && !pushDownCandidates.isEmpty();

        if (!hasGroups && !hasPushDown) {
            summaryLabel.setText("✅ No clones or push-down candidates found in " + fileName);
            JLabel empty = new JLabel("Nothing found. Your code is clean!");
            empty.setForeground(JBColor.GREEN.darker());
            empty.setBorder(new EmptyBorder(16, 0, 0, 0));
            resultsContainer.add(empty);
        } else {
            int groupCount = hasGroups ? groups.size() : 0;
            int pushDownCount = hasPushDown ? pushDownCandidates.size() : 0;
            summaryLabel.setText("⚠️ " + groupCount + " clone group(s), " + pushDownCount +
                    " push-down candidate(s) found in " + fileName);

            if (hasGroups) {
                JLabel sectionLabel = new JLabel("Clone Groups");
                sectionLabel.setFont(sectionLabel.getFont().deriveFont(Font.BOLD, 12f));
                sectionLabel.setForeground(JBColor.GRAY);
                sectionLabel.setBorder(new EmptyBorder(0, 2, 6, 0));
                resultsContainer.add(sectionLabel);
                for (int i = 0; i < groups.size(); i++) {
                    resultsContainer.add(buildGroupCard(groups.get(i), i + 1));
                    resultsContainer.add(Box.createVerticalStrut(8));
                }
            }

            if (hasPushDown) {
                if (hasGroups) resultsContainer.add(Box.createVerticalStrut(10));
                JLabel sectionLabel = new JLabel("Push Down Method Candidates");
                sectionLabel.setFont(sectionLabel.getFont().deriveFont(Font.BOLD, 12f));
                sectionLabel.setForeground(JBColor.GRAY);
                sectionLabel.setBorder(new EmptyBorder(0, 2, 6, 0));
                resultsContainer.add(sectionLabel);
                for (int i = 0; i < pushDownCandidates.size(); i++) {
                    resultsContainer.add(buildPushDownCard(pushDownCandidates.get(i), i + 1));
                    resultsContainer.add(Box.createVerticalStrut(8));
                }
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

        // Right: Refactor button — label now reflects the ACTUAL technique
        // that will run, computed via the same read-only check
        // triggerRefactor() uses at click-time (isPullUpApplicable()), not
        // a guess. Previously this always showed "Extract \u2192" for Type
        // 1/2/3 groups even when the pair qualified for Pull Up, which the
        // click would then silently perform anyway — correct behavior,
        // misleading label. Computing it here instead keeps the label and
        // the actual action in lock-step.
        String btnLabel;
        if (group.cloneType == CloneType.TYPE_4) {
            btnLabel = "Delegate →";
        } else {
            com.cloneguard.refactor.ExtractMethodEngine labelEngine =
                    com.cloneguard.refactor.ExtractMethodEngine.getInstance(project);
            // Same lookup triggerRefactor()'s no-file overload of
            // tryPullUpIfApplicable() relies on internally — "whatever
            // file is currently focused in the editor" — so the label
            // and the actual click-time behavior are always asking the
            // exact same question, not two different ones.
            Editor selectedEditor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            VirtualFile currentFile = (selectedEditor != null)
                    ? FileDocumentManager.getInstance().getFile(selectedEditor.getDocument())
                    : null;
            boolean wouldPullUp = currentFile != null
                    && labelEngine.isPullUpApplicable(currentFile, group.methods.get(0), group.methods.get(1));
            btnLabel = wouldPullUp ? "Pull Up →" : "Extract →";
        }
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

    private static final Color PUSH_DOWN_COLOR = new Color(76, 99, 168);

    private JPanel buildPushDownCard(PushDownCandidate candidate, int index) {
        JPanel card = new JPanel(new BorderLayout(8, 0));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PUSH_DOWN_COLOR, 2),
                new EmptyBorder(10, 12, 10, 12)
        ));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        card.setBackground(JBColor.background().brighter());

        JPanel info = new JPanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.setOpaque(false);

        JLabel typeLabel = new JLabel("#" + index + "  Push Down Candidate");
        typeLabel.setFont(typeLabel.getFont().deriveFont(Font.BOLD, 13f));
        typeLabel.setForeground(PUSH_DOWN_COLOR);

        JLabel methodLabel = new JLabel("Method: " + candidate.methodName + "()  on  " + candidate.superClassName);
        methodLabel.setFont(methodLabel.getFont().deriveFont(Font.PLAIN, 12f));

        JLabel detailLabel = new JLabel("Only used by: " + candidate.targetSubclassName);
        detailLabel.setFont(detailLabel.getFont().deriveFont(Font.PLAIN, 11f));
        detailLabel.setForeground(JBColor.GRAY);

        info.add(typeLabel);
        info.add(Box.createVerticalStrut(4));
        info.add(methodLabel);
        info.add(Box.createVerticalStrut(2));
        info.add(detailLabel);

        card.add(info, BorderLayout.CENTER);

        JButton pushDownBtn = new JButton("Push Down →");
        pushDownBtn.setBackground(PUSH_DOWN_COLOR);
        pushDownBtn.setForeground(Color.WHITE);
        pushDownBtn.setFocusPainted(false);
        pushDownBtn.addActionListener(e -> {
            for (JButton btn : activeRefactorButtons) {
                btn.setEnabled(false);
            }
            triggerPushDown(candidate);
        });
        activeRefactorButtons.add(pushDownBtn);
        card.add(pushDownBtn, BorderLayout.EAST);

        return card;
    }

    // ── PHASE 1: Route refactoring by clone type ─────────────────────────────
    //
    // Actual extraction/delegation/pull-up logic lives in ExtractMethodEngine
    // — shared with Scenario 1's gutter icon, so both entry points always run
    // through the exact same, extensively hand-tested implementation rather
    // than two separately-maintained copies. See ExtractMethodEngine for the
    // full list of safety measures.
    //
    // Type 4 semantic clones share no literal code by definition, so Extract
    // Method has nothing to work with there — routed to delegate() instead,
    // mirroring the same Extract-vs-Delegate split already built and
    // verified on the GitHub-bot side of CloneGuard (server.py).
    //
    // For Type 1/2/3 groups, Pull Up Method is now tried FIRST, before
    // falling back to Extract Method. tryPullUpIfApplicable() does its own
    // cheap PSI check (same class? shared non-Object superclass?) and
    // either fully handles the refactor and returns true, or does nothing
    // at all — no dialog, no side effects — and returns false, in which
    // case we fall through to the existing extract() call exactly as
    // before. This means the SAME "Extract →" button now transparently
    // becomes a Pull Up when the pair happens to sit in sibling subclasses,
    // with no new button or new user action required.
    private void triggerRefactor(CloneGroup group) {
        if (group.methods.size() < 2) {
            JOptionPane.showMessageDialog(root,
                    "Need at least 2 functions to refactor.",
                    "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String canonical = group.methods.get(0);
        String duplicate = group.methods.get(1);

        com.cloneguard.refactor.ExtractMethodEngine engine =
                com.cloneguard.refactor.ExtractMethodEngine.getInstance(project);

        if (group.cloneType == CloneType.TYPE_4) {
            engine.delegate(canonical, duplicate, group.cloneType.label, this::rescanCurrentFile);
        } else {
            boolean handledAsPullUp = engine.tryPullUpIfApplicable(
                    canonical, duplicate, group.cloneType.label, this::rescanCurrentFile);
            if (!handledAsPullUp) {
                engine.extract(canonical, duplicate, group.cloneType.label, this::rescanCurrentFile);
            }
        }
    }

    // ── Push Down trigger — separate from triggerRefactor() above since
    // push-down candidates aren't CloneGroups (no pair, no clone type).
    private void triggerPushDown(PushDownCandidate candidate) {
        com.cloneguard.refactor.ExtractMethodEngine.getInstance(project)
                .pushDown(candidate.methodName, candidate.targetSubclassName, this::rescanCurrentFile);
    }


    // ── Re-scan the current file and refresh the tool window with fresh results ──
    private void rescanCurrentFile(PsiFile psiFile) {
        FileScannerService scanner = project.getService(FileScannerService.class);
        if (scanner == null) return;

        List<CloneGroup> freshGroups = ReadAction.compute(() -> scanner.scanFile(psiFile));
        List<PushDownCandidate> freshPushDownCandidates = ReadAction.compute(() -> scanner.findPushDownCandidates(psiFile));
        ApplicationManager.getApplication().invokeLater(() ->
                CloneGuardToolWindowFactory.showResults(project, freshGroups, freshPushDownCandidates, psiFile.getName())
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
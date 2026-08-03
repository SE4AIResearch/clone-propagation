package com.cloneguard.ui;

import com.cloneguard.model.RefactorSession;
import com.cloneguard.services.MetricsTrackerService;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

import java.util.List;

/**
 * Trend Dashboard — a second tab in the CloneGuard tool window, next to
 * Scan Results. Shows the before/after quality trend tracked by
 * MetricsTrackerService: one point per scan SESSION (see that class for
 * the exact session lifecycle — a session runs from one manual "Scan
 * Current File" to the next, and only becomes a data point at all if at
 * least one refactor was applied during it).
 *
 * The line chart is custom-painted rather than built on an external
 * charting library — this plugin's Gradle dependencies weren't
 * available to verify during development, so rather than guess at
 * whether a new library could be safely added, this draws directly with
 * Graphics2D. It's a deliberately simple two-line chart (LOC before /
 * LOC after per session), not a general-purpose charting component.
 *
 * CHANGED: complexity, WMC, CBO, DIT, and NOC are now sourced from
 * SciTools Understand rather than an in-house PSI-based formula (see
 * MetricsTrackerService / UnderstandMetricsService). Since Understand
 * is an external, separately-licensed desktop tool, any given session
 * may have RefactorSession.understandAvailable == false if it wasn't
 * reachable when that session was recorded -- the metrics row below
 * checks this explicitly and shows a clear "Understand not available"
 * notice instead of silently displaying zeros that would look like a
 * genuinely clean, zero-complexity file.
 */
public class TrendDashboardPanel {

    private final Project project;
    private final JPanel root;
    private final JLabel fileLabel;
    private final JLabel summaryLabel;
    private final ChartPanel chartPanel;
    private JPanel locCard;
    // NEW (project-wide dashboard support): tracks whether the toggle in
    // the top bar is currently set to "Whole Project (Average)" rather
    // than "This File" -- checked by the Refresh button so it re-runs
    // whichever view is actually showing.
    private boolean viewingProjectAverage = false;
    private final JPanel breakdownContainer;
    private final JPanel cloneTypeBreakdownContainer;
    private final JPanel understandMetricsContainer;
    private final JLabel understandStatusLabel;
    private final JLabel understandLegendLabel;
    private final JPanel centerPanel;

    // Which file's trend is currently being shown — set by
    // CloneGuardToolWindowFactory.refreshTrendDashboard() right after
    // every scan, so the dashboard always reflects whichever file was
    // just scanned, never a mix of every file ever scanned. Null until
    // the first scan of this IDE session happens.
    private String currentFileName;

    public TrendDashboardPanel(Project project) {
        this.project = project;
        this.root = new JPanel(new BorderLayout());

        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBorder(new EmptyBorder(8, 12, 8, 12));
        topBar.setBackground(JBColor.background());

        JPanel topBarText = new JPanel();
        topBarText.setLayout(new BoxLayout(topBarText, BoxLayout.Y_AXIS));
        topBarText.setOpaque(false);

        fileLabel = new JLabel("No file scanned yet");
        fileLabel.setFont(fileLabel.getFont().deriveFont(Font.BOLD, 12f));
        topBarText.add(fileLabel);

        summaryLabel = new JLabel("Scan a file to see its trend.");
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.PLAIN, 12f));
        topBarText.add(summaryLabel);

        topBar.add(topBarText, BorderLayout.WEST);

        // NEW (project-wide dashboard support): toggle between the
        // existing per-file trend view (unchanged) and a new project-wide
        // average view. Deliberately a separate reload path
        // (reloadProjectAverage() below) rather than folding this into
        // reload() itself -- reload()'s existing per-file logic has
        // several already-tested branches (empty state, missing
        // Understand data, etc.) that don't need to be touched or
        // risked just to add a second view mode alongside them.
        JPanel viewModePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        viewModePanel.setOpaque(false);
        ButtonGroup viewModeGroup = new ButtonGroup();
        JToggleButton thisFileToggle = new JToggleButton("This File", true);
        JToggleButton projectToggle = new JToggleButton("Whole Project (Average)");
        viewModeGroup.add(thisFileToggle);
        viewModeGroup.add(projectToggle);
        thisFileToggle.addActionListener(e -> { viewingProjectAverage = false; reload(); });
        projectToggle.addActionListener(e -> { viewingProjectAverage = true; reloadProjectAverage(); });
        viewModePanel.add(thisFileToggle);
        viewModePanel.add(projectToggle);
        topBar.add(viewModePanel, BorderLayout.CENTER);

        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> {
            if (viewingProjectAverage) reloadProjectAverage(); else reload();
        });
        topBar.add(refreshBtn, BorderLayout.EAST);

        root.add(topBar, BorderLayout.NORTH);

        centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setBorder(new EmptyBorder(8, 12, 8, 12));

        // FIX (professor-flagged): the LOC chart used to be a large
        // standalone panel (400x220) at the top, with the five Understand
        // metrics crammed into a small FlowLayout row of tiny 56x30 chips
        // underneath -- a real visual imbalance, since LOC read as "the
        // important one" purely by size while five equally meaningful
        // metrics looked like an afterthought. Restructured into a single
        // GridLayout(2, 3) of six EQUALLY-SIZED cards -- LOC plus all
        // five Understand metrics -- so every metric gets the same visual
        // weight. LOC keeps its own multi-session ChartPanel internally
        // (it's the one metric tracked across every session, not just
        // latest-session before/after), just resized to match the other
        // five cards instead of dominating the section.
        JLabel metricsLabel = new JLabel("Trend metrics");
        metricsLabel.setFont(metricsLabel.getFont().deriveFont(Font.BOLD, 12f));
        metricsLabel.setBorder(new EmptyBorder(0, 0, 6, 0));
        metricsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        centerPanel.add(metricsLabel);

        understandStatusLabel = new JLabel(" ");
        understandStatusLabel.setForeground(JBColor.GRAY);
        understandStatusLabel.setFont(understandStatusLabel.getFont().deriveFont(Font.ITALIC, 12f));
        understandStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        centerPanel.add(understandStatusLabel);

        understandMetricsContainer = new JPanel(new GridLayout(2, 3, 12, 12));
        understandMetricsContainer.setAlignmentX(Component.LEFT_ALIGNMENT);
        understandMetricsContainer.setOpaque(false);

        JPanel locCardBuild = new JPanel(new BorderLayout());
        locCardBuild.setOpaque(false);
        JLabel locTitle = new JLabel("Lines of code (all sessions)");
        locTitle.setFont(locTitle.getFont().deriveFont(Font.BOLD, 11f));
        locTitle.setBorder(new EmptyBorder(0, 0, 4, 0));
        locCardBuild.add(locTitle, BorderLayout.NORTH);
        chartPanel = new ChartPanel();
        chartPanel.setPreferredSize(new Dimension(METRIC_CARD_WIDTH, METRIC_CARD_HEIGHT));
        locCardBuild.add(chartPanel, BorderLayout.CENTER);
        this.locCard = locCardBuild;
        understandMetricsContainer.add(locCard);

        centerPanel.add(understandMetricsContainer);

        centerPanel.add(Box.createVerticalStrut(14));

        JLabel breakdownLabel = new JLabel("Refactor type breakdown (all sessions)");
        breakdownLabel.setFont(breakdownLabel.getFont().deriveFont(Font.BOLD, 12f));
        breakdownLabel.setBorder(new EmptyBorder(0, 0, 6, 0));
        breakdownLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        centerPanel.add(breakdownLabel);

        breakdownContainer = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        breakdownContainer.setAlignmentX(Component.LEFT_ALIGNMENT);
        breakdownContainer.setOpaque(false);
        centerPanel.add(breakdownContainer);

        centerPanel.add(Box.createVerticalStrut(14));

        JLabel cloneTypeLabel = new JLabel("Clone type breakdown (all sessions)");
        cloneTypeLabel.setFont(cloneTypeLabel.getFont().deriveFont(Font.BOLD, 12f));
        cloneTypeLabel.setBorder(new EmptyBorder(0, 0, 6, 0));
        cloneTypeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        centerPanel.add(cloneTypeLabel);

        cloneTypeBreakdownContainer = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        cloneTypeBreakdownContainer.setAlignmentX(Component.LEFT_ALIGNMENT);
        cloneTypeBreakdownContainer.setOpaque(false);
        centerPanel.add(cloneTypeBreakdownContainer);

        centerPanel.add(Box.createVerticalStrut(14));

        // NEW: always-visible legend spelling out each metric's full name
        // and a one-line explanation -- the cards above stay short (CC,
        // WMC, LOC...) so the grid itself stays compact and readable, but
        // the full meaning is always visible here rather than hidden
        // behind a hover tooltip a first-time viewer might never
        // discover.
        understandLegendLabel = new JLabel(understandLegendHtml());
        understandLegendLabel.setFont(understandLegendLabel.getFont().deriveFont(Font.PLAIN, 11f));
        understandLegendLabel.setForeground(JBColor.GRAY);
        understandLegendLabel.setBorder(new EmptyBorder(6, 0, 0, 0));
        understandLegendLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        centerPanel.add(understandLegendLabel);

        JBScrollPane scroll = new JBScrollPane(centerPanel);
        scroll.setBorder(null);
        root.add(scroll, BorderLayout.CENTER);

        reload();
    }

    // Shared target size for every card in the metrics grid -- LOC's
    // chart and each Understand metric's bar-pair card all render into
    // exactly this footprint, which is the whole point of the fix: no
    // metric reads as "more important" than another purely because its
    // panel happens to be bigger.
    private static final int METRIC_CARD_WIDTH = 240;
    private static final int METRIC_CARD_HEIGHT = 170;

    public JPanel getRoot() {
        return root;
    }

    /**
     * Called by CloneGuardToolWindowFactory.refreshTrendDashboard() right
     * after every scan, with whichever file was just scanned. This is
     * what makes the dashboard per-file: switching to a different file
     * and scanning it re-filters the whole view to THAT file's history,
     * and switching back to a previously-scanned file resumes exactly
     * where that file's own trend left off — never mixed with any other
     * file's sessions.
     */
    public void setCurrentFile(String fileName) {
        this.currentFileName = fileName;
        reload();
    }

    /**
     * Re-reads metrics.jsonl, filtered to currentFileName, and repaints
     * everything. Called on construction (with no file selected yet),
     * whenever the user clicks Refresh, and automatically right after
     * every scan via setCurrentFile() above.
     */
    public void reload() {
        if (currentFileName == null) {
            fileLabel.setText("No file scanned yet");
            summaryLabel.setText("Scan a file to see its trend.");
            chartPanel.setSessions(List.of());
            breakdownContainer.removeAll();
            breakdownContainer.revalidate();
            breakdownContainer.repaint();
            cloneTypeBreakdownContainer.removeAll();
            cloneTypeBreakdownContainer.revalidate();
            cloneTypeBreakdownContainer.repaint();
            understandMetricsContainer.removeAll();
            understandMetricsContainer.revalidate();
            understandMetricsContainer.repaint();
            understandStatusLabel.setText(" ");
            centerPanel.revalidate();
            centerPanel.repaint();
            root.revalidate();
            root.repaint();
            return;
        }

        fileLabel.setText(currentFileName);

        List<RefactorSession> sessions = MetricsTrackerService.getInstance(project).loadSessionsForFile(currentFileName);

        if (sessions.isEmpty()) {
            summaryLabel.setText("No refactor sessions recorded yet for this file — apply a refactor, then scan again to see a trend.");
            chartPanel.setSessions(sessions);
            breakdownContainer.removeAll();
            breakdownContainer.revalidate();
            breakdownContainer.repaint();
            cloneTypeBreakdownContainer.removeAll();
            cloneTypeBreakdownContainer.revalidate();
            cloneTypeBreakdownContainer.repaint();
            understandMetricsContainer.removeAll();
            understandMetricsContainer.revalidate();
            understandMetricsContainer.repaint();
            understandStatusLabel.setText(" ");
            // FIX (found live, Trend Dashboard testing): clicking the
            // Refresh button visibly did nothing, even though the SAME
            // underlying reload() call worked correctly when triggered
            // automatically right after a scan. The data itself was
            // never wrong — confirmed live, the numbers that eventually
            // did appear were exactly correct. The difference was purely
            // visual: chartPanel is a custom Graphics2D-painted component
            // nested inside a BoxLayout inside a JScrollPane, and calling
            // repaint() on just that inner panel doesn't reliably
            // cascade through that specific nesting -- a known category
            // of Swing issue where a child's own repaint() call isn't
            // enough if its container hierarchy's layout/paint state
            // wasn't also explicitly invalidated. Forcing revalidate()
            // and repaint() on the top-level root container guarantees a
            // full layout + paint pass every time reload() runs,
            // regardless of which specific call site triggered it.
            centerPanel.revalidate();
            centerPanel.repaint();
            root.revalidate();
            root.repaint();
            return;
        }

        int totalNetLines = 0;
        int totalNetComplexity = 0;
        int totalDuplicatedEliminated = 0;
        int totalRefactors = 0;
        int extractTotal = 0, delegateTotal = 0, pullUpTotal = 0, pushDownTotal = 0;
        int type1Total = 0, type2Total = 0, type3Total = 0, type4Total = 0;

        for (RefactorSession s : sessions) {
            totalNetLines += s.netLinesChanged();
            if (s.understandAvailable) {
                totalNetComplexity += s.netComplexityChanged();
            }
            totalDuplicatedEliminated += s.duplicatedLinesEliminated;
            totalRefactors += s.totalRefactors();
            extractTotal += s.extractCount;
            delegateTotal += s.delegateCount;
            pullUpTotal += s.pullUpCount;
            pushDownTotal += s.pushDownCount;
            type1Total += s.type1Count;
            type2Total += s.type2Count;
            type3Total += s.type3Count;
            type4Total += s.type4Count;
        }

        summaryLabel.setText(String.format(
                "%d session(s)  |  net %+d line(s)  |  net %+d complexity  |  %d duplicated line(s) eliminated  |  %d refactor(s) applied",
                sessions.size(), totalNetLines, totalNetComplexity, totalDuplicatedEliminated, totalRefactors));

        // FIX (found live): the summary line packs "line(s)" and
        // "complexity" right next to each other with no explanation of
        // how they differ -- easy to read as two views of the same
        // number, when they're actually measuring different things.
        // Lines is a volume count; complexity counts branching (if/for/
        // while/switch/catch/ternary/&&/||). A refactor can shrink one
        // without moving the other at all. A tooltip on hover explains
        // this without cluttering the compact summary line itself.
        summaryLabel.setToolTipText(
                "<html><b>Line(s)</b> counts raw file size — how much text changed.<br>"
                        + "<b>Complexity</b> counts branching (if/for/while/switch/catch/ternary/&amp;&amp;/||) —<br>"
                        + "how many independent paths a reader has to follow.<br>"
                        + "A refactor can move one without moving the other.<br>"
                        + "Complexity, WMC, CBO, DIT, and NOC are computed by SciTools Understand.</html>");

        chartPanel.setSessions(sessions);

        breakdownContainer.removeAll();
        breakdownContainer.add(breakdownChip("Extract", extractTotal, new Color(224, 85, 79)));
        breakdownContainer.add(breakdownChip("Delegate", delegateTotal, new Color(217, 154, 61)));
        breakdownContainer.add(breakdownChip("Pull up", pullUpTotal, new Color(90, 143, 214)));
        breakdownContainer.add(breakdownChip("Push down", pushDownTotal, new Color(123, 111, 207)));
        breakdownContainer.revalidate();
        breakdownContainer.repaint();

        // EXTENDED: which CLONE TYPE each applied refactor was originally
        // fixing -- a different question from which TECHNIQUE fixed it
        // (the row above). A Type 4 clone fixed via Delegate and a Type 1
        // clone fixed via Pull Up both show up here by their ORIGINAL
        // type, not by which button was clicked.
        cloneTypeBreakdownContainer.removeAll();
        cloneTypeBreakdownContainer.add(breakdownChip("Type 1", type1Total, new Color(197, 90, 17)));
        cloneTypeBreakdownContainer.add(breakdownChip("Type 2", type2Total, new Color(184, 134, 11)));
        cloneTypeBreakdownContainer.add(breakdownChip("Type 3", type3Total, new Color(46, 139, 87)));
        cloneTypeBreakdownContainer.add(breakdownChip("Type 4", type4Total, new Color(99, 90, 197)));
        cloneTypeBreakdownContainer.revalidate();
        cloneTypeBreakdownContainer.repaint();

        // NEW: Understand-derived metrics for the MOST RECENT session
        // only (WMC/CBO/DIT/NOC are point-in-time class design metrics,
        // not something that reads naturally as a running total the way
        // "refactors applied" does). Falls back to a clear status
        // message rather than showing misleading zeros if Understand
        // wasn't reachable when that session was recorded.
        //
        // FIX (professor-flagged layout pass): understandMetricsContainer
        // now also holds the LOC card (see constructor), so removeAll()
        // here would wipe that out too if not re-added immediately below.
        // Also: when Understand data isn't available, five empty
        // placeholder cards are added rather than leaving the grid with
        // just one filled cell -- keeps the 2x3 grid looking intentional
        // and consistent in EITHER state, not just when data is present.
        RefactorSession latest = sessions.get(sessions.size() - 1);
        understandMetricsContainer.removeAll();
        understandMetricsContainer.add(locCard);
        if (latest.understandAvailable) {
            understandStatusLabel.setText(" ");
            // Each card below gets a short one-line tooltip explaining
            // what the metric means -- hover to see it, since six
            // acronyms in a row means nothing to a first-time viewer.
            understandMetricsContainer.add(understandBarChip("CC", latest.complexityBefore, latest.complexityAfter, new Color(99, 90, 197),
                    "Cyclomatic Complexity (worst method in the class): number of independent decision paths through it."));
            understandMetricsContainer.add(understandBarChip("WMC", latest.wmcBefore, latest.wmcAfter, new Color(46, 139, 87),
                    "Weighted Methods per Class: sum of every method's complexity in the class."));
            understandMetricsContainer.add(understandBarChip("CBO", latest.cboBefore, latest.cboAfter, new Color(197, 90, 17),
                    "Coupling Between Objects: how many other classes this class references."));
            understandMetricsContainer.add(understandBarChip("DIT", latest.ditBefore, latest.ditAfter, new Color(184, 134, 11),
                    "Depth of Inheritance Tree: how many levels up the class hierarchy this class sits."));
            understandMetricsContainer.add(understandBarChip("NOC", latest.nocBefore, latest.nocAfter, new Color(90, 143, 214),
                    "Number of Children: how many other classes directly extend this class."));
        } else {
            understandStatusLabel.setText(
                    "Understand not available for the most recent session — install and license SciTools Understand, "
                            + "with 'und' on your PATH, to see WMC/CBO/DIT/NOC here.");
            for (String metricName : new String[]{"CC", "WMC", "CBO", "DIT", "NOC"}) {
                understandMetricsContainer.add(unavailableMetricCard(metricName));
            }
        }
        understandMetricsContainer.revalidate();
        understandMetricsContainer.repaint();

        // Same safety net as the empty-state branch above — guarantees
        // the chart and every other child actually repaints, regardless
        // of whether this ran from the Refresh button or the automatic
        // post-scan call.
        centerPanel.revalidate();
        centerPanel.repaint();
        root.revalidate();
        root.repaint();
    }

    /**
     * NEW (project-wide dashboard support): the "Whole Project (Average)"
     * counterpart to reload() above. Reads MetricsTrackerService's
     * getProjectAverageMetrics() (averages the latest recorded session
     * per file, across every file in the project that has Understand
     * data) and renders the same six-card grid layout reload() uses --
     * same visual language, so switching the toggle doesn't feel like a
     * different tool, just a different scope. The LOC card and the
     * refactor-type/clone-type breakdown rows below it are intentionally
     * left showing the last-loaded per-file data rather than also being
     * reimplemented as project-wide aggregates -- those breakdowns are
     * naturally cumulative counts already tracked per file, and a
     * genuinely project-wide version of THOSE specifically would need
     * its own separate design pass, out of scope for this change.
     */
    public void reloadProjectAverage() {
        // NEW (professor-requested follow-up): show the ACTUAL project
        // name here, not a generic "Whole Project" label -- matters once
        // someone has multiple projects open across different windows,
        // so this dashboard is unambiguous about which project's data
        // it's showing.
        fileLabel.setText(project.getName() + " (Whole Project Average)");

        MetricsTrackerService.ProjectMetricsAverage avg =
                MetricsTrackerService.getInstance(project).getProjectAverageMetrics();

        if (avg.filesWithUnderstandData == 0) {
            summaryLabel.setText(avg.fileCount == 0
                    ? "No refactor sessions recorded yet anywhere in this project."
                    : avg.fileCount + " file(s) have recorded sessions, but none have Understand data available yet.");
            understandMetricsContainer.removeAll();
            understandMetricsContainer.add(locCard);
            for (String metricName : new String[]{"CC", "WMC", "CBO", "DIT", "NOC"}) {
                understandMetricsContainer.add(unavailableMetricCard(metricName));
            }
            understandMetricsContainer.revalidate();
            understandMetricsContainer.repaint();
            understandStatusLabel.setText(" ");
            centerPanel.revalidate();
            centerPanel.repaint();
            root.revalidate();
            root.repaint();
            return;
        }

        // NEW (professor-requested follow-up): spell out WHICH files
        // were actually included, not just "3 of 4" -- if a file was
        // excluded (no Understand data for its latest session), name it
        // too, so the gap between fileCount and filesWithUnderstandData
        // is never a mystery the viewer has to go dig for elsewhere.
        String includedList = String.join(", ", avg.includedFileNames);
        String summaryText = "Averaged across " + avg.filesWithUnderstandData + " of " + avg.fileCount
                + " file(s): " + includedList;
        if (avg.filesWithUnderstandData < avg.fileCount) {
            java.util.List<String> excluded = new java.util.ArrayList<>(avg.allFileNames);
            excluded.removeAll(avg.includedFileNames);
            summaryText += "  (excluded, no Understand data: " + String.join(", ", excluded) + ")";
        }
        summaryLabel.setText(summaryText);
        summaryLabel.setToolTipText(summaryText);

        understandStatusLabel.setText(" ");
        understandMetricsContainer.removeAll();
        understandMetricsContainer.add(locCard);
        understandMetricsContainer.add(averageBarChip("CC", avg.ccBefore, avg.ccAfter, new Color(99, 90, 197),
                "Average Cyclomatic Complexity (worst method in class) across all files with Understand data."));
        understandMetricsContainer.add(averageBarChip("WMC", avg.wmcBefore, avg.wmcAfter, new Color(46, 139, 87),
                "Average Weighted Methods per Class across all files with Understand data."));
        understandMetricsContainer.add(averageBarChip("CBO", avg.cboBefore, avg.cboAfter, new Color(197, 90, 17),
                "Average Coupling Between Objects across all files with Understand data."));
        understandMetricsContainer.add(averageBarChip("DIT", avg.ditBefore, avg.ditAfter, new Color(184, 134, 11),
                "Average Depth of Inheritance Tree across all files with Understand data."));
        understandMetricsContainer.add(averageBarChip("NOC", avg.nocBefore, avg.nocAfter, new Color(90, 143, 214),
                "Average Number of Children across all files with Understand data."));
        understandMetricsContainer.revalidate();
        understandMetricsContainer.repaint();

        centerPanel.revalidate();
        centerPanel.repaint();
        root.revalidate();
        root.repaint();
    }

    /**
     * Same visual card as understandBarChip(), but for a DOUBLE
     * before/after pair (an average across files) rather than a single
     * session's exact int values -- shown with one decimal place so an
     * average of, say, 7.4 doesn't silently round away from a real 7 or
     * 8 and look like more precision (or less) than it actually has.
     */
    private JPanel averageBarChip(String label, double before, double after, Color color, String tooltip) {
        JPanel card = new JPanel(new BorderLayout());
        card.setOpaque(false);
        card.setToolTipText(tooltip);
        card.setPreferredSize(new Dimension(METRIC_CARD_WIDTH, METRIC_CARD_HEIGHT));

        JLabel nameLabel = new JLabel(label);
        nameLabel.setForeground(color);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 11f));
        nameLabel.setBorder(new EmptyBorder(0, 0, 4, 0));
        card.add(nameLabel, BorderLayout.NORTH);

        MiniBarPair bars = new MiniBarPair((int) Math.round(before), (int) Math.round(after), color);
        bars.setToolTipText(tooltip);
        card.add(bars, BorderLayout.CENTER);

        JLabel valueLabel = new JLabel(String.format("%.1f \u2192 %.1f", before, after), SwingConstants.CENTER);
        valueLabel.setForeground(JBColor.GRAY);
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.PLAIN, 11f));
        valueLabel.setBorder(new EmptyBorder(4, 0, 0, 0));
        card.add(valueLabel, BorderLayout.SOUTH);

        return card;
    }

    private JLabel breakdownChip(String label, int count, Color color) {
        JLabel chip = new JLabel("\u25CF " + label + (count > 0 ? " " + count : ""));
        chip.setForeground(color);
        chip.setFont(chip.getFont().deriveFont(Font.PLAIN, 12f));
        return chip;
    }

    /** Same visual style as breakdownChip, but always shows "Label: value" -- used for the Understand metrics row, where 0 is a genuine, meaningful value (not "nothing happened yet"). */
    /**
     * Small always-visible reference block: full name + one-line meaning
     * for each of the six Understand-derived metrics, in the same order
     * the chips above appear (CC, WMC, LOC, CBO, DIT, NOC).
     */
    private static String understandLegendHtml() {
        return "<html>"
                + "<b>CC</b> = Cyclomatic Complexity (worst method in the class): number of independent decision paths through it.<br>"
                + "<b>WMC</b> = Weighted Methods per Class: sum of every method's complexity in the class.<br>"
                + "<b>CBO</b> = Coupling Between Objects: how many other classes this class references.<br>"
                + "<b>DIT</b> = Depth of Inheritance Tree: how many levels up the class hierarchy this class sits.<br>"
                + "<b>NOC</b> = Number of Children: how many other classes directly extend this class."
                + "</html>";
    }

    private JLabel understandChip(String label, int value, Color color, String tooltip) {
        JLabel chip = new JLabel("\u25CF " + label + ": " + value);
        chip.setForeground(color);
        chip.setFont(chip.getFont().deriveFont(Font.PLAIN, 12f));
        chip.setToolTipText(tooltip);
        return chip;
    }

    /**
     * Bar-visualization version of understandChip() above: same label,
     * color, and tooltip, but now also draws a small before/after bar
     * pair (muted gray vs the metric's color) instead of showing only
     * the raw number. Mirrors the same visual language the LOC chart
     * already uses elsewhere on this panel -- muted bar for "before",
     * colored bar for "after" -- so a viewer doesn't need to learn a
     * second visual convention just because this row uses a single
     * latest-session snapshot instead of a multi-session trend line.
     */
    private JPanel understandBarChip(String label, int before, int after, Color color, String tooltip) {
        JPanel card = new JPanel(new BorderLayout());
        card.setOpaque(false);
        card.setToolTipText(tooltip);
        card.setPreferredSize(new Dimension(METRIC_CARD_WIDTH, METRIC_CARD_HEIGHT));

        JLabel nameLabel = new JLabel(label);
        nameLabel.setForeground(color);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 11f));
        nameLabel.setBorder(new EmptyBorder(0, 0, 4, 0));
        card.add(nameLabel, BorderLayout.NORTH);

        MiniBarPair bars = new MiniBarPair(before, after, color);
        bars.setToolTipText(tooltip);
        card.add(bars, BorderLayout.CENTER);

        JLabel valueLabel = new JLabel(before + " \u2192 " + after, SwingConstants.CENTER);
        valueLabel.setForeground(JBColor.GRAY);
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.PLAIN, 11f));
        valueLabel.setBorder(new EmptyBorder(4, 0, 0, 0));
        card.add(valueLabel, BorderLayout.SOUTH);

        return card;
    }

    /**
     * Placeholder shown in place of a real metric card when the latest
     * session has no Understand data -- keeps the 2x3 grid's shape
     * consistent (six cells, always) rather than the layout collapsing
     * down to a single LOC card with nothing beside it, which would look
     * broken rather than intentional.
     */
    private JPanel unavailableMetricCard(String label) {
        JPanel card = new JPanel(new BorderLayout());
        card.setOpaque(false);
        card.setPreferredSize(new Dimension(METRIC_CARD_WIDTH, METRIC_CARD_HEIGHT));

        JLabel nameLabel = new JLabel(label);
        nameLabel.setForeground(JBColor.GRAY);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 11f));
        nameLabel.setBorder(new EmptyBorder(0, 0, 4, 0));
        card.add(nameLabel, BorderLayout.NORTH);

        JLabel dash = new JLabel("—", SwingConstants.CENTER);
        dash.setForeground(JBColor.GRAY);
        dash.setFont(dash.getFont().deriveFont(Font.PLAIN, 20f));
        card.add(dash, BorderLayout.CENTER);

        return card;
    }

    /**
     * Custom-painted paired-bar card: "before" (muted gray) next to
     * "after" (the metric's own color), each scaled against this
     * metric's own before/after max -- deliberately NOT scaled against
     * the other metrics, since CC and CBO can differ by an order of
     * magnitude and forcing one shared scale would make the smaller
     * metric's bars unreadably thin.
     *
     * FIX (professor-flagged layout pass): this used to be a tiny
     * 56x30 decoration next to a plain numeric label -- exactly the
     * "LOC chart big, everything else small" imbalance that was
     * flagged. Resized to fill the same METRIC_CARD_WIDTH x (roughly
     * two-thirds of METRIC_CARD_HEIGHT, leaving room for the title and
     * value labels around it) footprint every other card in the grid
     * uses, so this reads with the same visual weight as the LOC chart
     * instead of looking like an afterthought underneath it.
     */
    private static class MiniBarPair extends JPanel {
        private final int before;
        private final int after;
        private final Color afterColor;
        private static final int BAR_W = 48;

        MiniBarPair(int before, int after, Color afterColor) {
            this.before = before;
            this.after = after;
            this.afterColor = afterColor;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();
            int max = Math.max(1, Math.max(before, after));
            int baseline = height - 4;
            int usableHeight = height - 12;
            int beforeH = (int) Math.round((before / (double) max) * usableHeight);
            int afterH = (int) Math.round((after / (double) max) * usableHeight);

            int gap = 24;
            int totalBarsWidth = BAR_W * 2 + gap;
            int beforeX = Math.max(4, (width - totalBarsWidth) / 2);
            int afterX = beforeX + BAR_W + gap;

            g2.setColor(JBColor.GRAY);
            g2.fillRect(beforeX, baseline - beforeH, BAR_W, Math.max(1, beforeH));
            g2.setColor(afterColor);
            g2.fillRect(afterX, baseline - afterH, BAR_W, Math.max(1, afterH));

            g2.setColor(JBColor.border());
            g2.drawLine(0, baseline, width, baseline);

            // Small "before"/"after" axis labels under each bar, matching
            // the LOC chart's convention of labeling what's plotted rather
            // than relying only on the value label below the whole card.
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 9f));
            g2.setColor(JBColor.GRAY);
            drawCentered(g2, "before", beforeX + BAR_W / 2, baseline + 12);
            drawCentered(g2, "after", afterX + BAR_W / 2, baseline + 12);

            g2.dispose();
        }

        private void drawCentered(Graphics2D g2, String text, int centerX, int y) {
            int textWidth = g2.getFontMetrics().stringWidth(text);
            g2.drawString(text, centerX - textWidth / 2, y);
        }
    }

    /**
     * Minimal custom-painted line chart: LOC before (dashed, gray) vs
     * LOC after (solid, green) across sessions, oldest to newest, left
     * to right. Deliberately simple — no zoom, no tooltips, no external
     * library — since the goal is a readable trend at a glance, not a
     * full analytics tool.
     */
    private static class ChartPanel extends JPanel {
        private List<RefactorSession> sessions = List.of();

        void setSessions(List<RefactorSession> sessions) {
            this.sessions = sessions;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int padLeft = 40, padRight = 16, padTop = 26, padBottom = 24;

            g2.setColor(JBColor.background().brighter());
            g2.fillRect(0, 0, w, h);

            if (sessions.isEmpty()) {
                g2.setColor(JBColor.GRAY);
                g2.drawString("No data yet", padLeft, h / 2);
                g2.dispose();
                return;
            }

            // Legend, since grey-vs-colored bars mean nothing without a
            // key explaining them. Kept in the top padding strip, above
            // the chart's own drawing area, so it never overlaps data.
            drawLegend(g2, padLeft);

            int n = sessions.size();
            int maxLoc = 1;
            for (RefactorSession s : sessions) {
                maxLoc = Math.max(maxLoc, Math.max(s.locBefore, s.locAfter));
            }
            // 10% headroom so the highest point isn't flush against the top edge.
            maxLoc = (int) Math.ceil(maxLoc * 1.1);

            int chartW = w - padLeft - padRight;
            int chartH = h - padTop - padBottom;

            g2.setColor(JBColor.GRAY.darker());
            for (int i = 0; i <= 4; i++) {
                int y = padTop + chartH - (chartH * i / 4);
                g2.drawLine(padLeft, y, padLeft + chartW, y);
                int value = maxLoc * i / 4;
                g2.setColor(JBColor.GRAY);
                g2.drawString(String.valueOf(value), 4, y + 4);
                g2.setColor(JBColor.GRAY.darker());
            }

            int[] xs = new int[n];
            for (int i = 0; i < n; i++) {
                xs[i] = (n == 1) ? padLeft + chartW / 2 : padLeft + (chartW * i / (n - 1));
            }

            // FIX (found live, requested change v2): the box-per-session
            // approach (spanning a range from "before" to "after") tested
            // worse in practice than expected -- reverting to a more
            // conventional grouped-bar histogram instead: each session
            // gets two adjacent bars, one for "before" and one for
            // "after", both starting from the same zero baseline like a
            // standard bar chart, rather than floating boxes representing
            // a range. This is a more familiar chart shape to read at a
            // glance, and makes it obvious this is a per-session
            // comparison rather than a continuous trend line.
            int groupWidth = Math.max(10, Math.min(36, chartW / Math.max(1, n) - 4));
            int barWidth = Math.max(3, groupWidth / 2 - 1);
            for (int i = 0; i < n; i++) {
                drawSessionBars(g2, sessions.get(i), xs[i], barWidth, padTop, chartH, maxLoc);
            }

            g2.setColor(JBColor.GRAY);
            int labelStep = Math.max(1, n / 8);
            for (int i = 0; i < n; i += labelStep) {
                g2.drawString("S" + (i + 1), xs[i] - 8, h - 6);
            }

            g2.dispose();
        }

        /**
         * Draws a small color key above the chart: grey = before, green =
         * after (improved), orange = after (grew). Without this, grey vs
         * colored bars mean nothing to a first-time viewer.
         */
        private void drawLegend(Graphics2D g2, int startX) {
            int swatchSize = 10;
            int y = 4;
            int x = startX;

            Color grey = JBColor.GRAY;
            Color green = new JBColor(new Color(46, 160, 90), new Color(90, 200, 130));
            Color orange = new JBColor(new Color(214, 100, 40), new Color(230, 140, 80));

            x = drawLegendItem(g2, x, y, swatchSize, grey, "Before");
            x = drawLegendItem(g2, x, y, swatchSize, green, "After — improved");
            drawLegendItem(g2, x, y, swatchSize, orange, "After — grew");
        }

        private int drawLegendItem(Graphics2D g2, int x, int y, int swatchSize, Color color, String label) {
            g2.setColor(color);
            g2.fillRect(x, y, swatchSize, swatchSize);
            g2.setColor(JBColor.GRAY.darker());
            g2.drawRect(x, y, swatchSize, swatchSize);

            g2.setColor(JBColor.GRAY);
            int textX = x + swatchSize + 4;
            g2.drawString(label, textX, y + swatchSize);

            FontMetrics fm = g2.getFontMetrics();
            return textX + fm.stringWidth(label) + 14;
        }

        private void drawSessionBars(Graphics2D g2, RefactorSession s, int x, int barWidth,
                                      int padTop, int chartH, int maxLoc) {
            int baseline = padTop + chartH;
            int beforeHeight = (int) ((long) chartH * s.locBefore / maxLoc);
            int afterHeight = (int) ((long) chartH * s.locAfter / maxLoc);

            boolean improved = s.locAfter <= s.locBefore;
            Color afterFill = improved
                    ? new JBColor(new Color(46, 160, 90), new Color(90, 200, 130))
                    : new JBColor(new Color(214, 100, 40), new Color(230, 140, 80));

            int beforeX = x - barWidth - 1;
            int afterX = x + 1;

            g2.setColor(JBColor.GRAY);
            g2.fillRect(beforeX, baseline - beforeHeight, barWidth, beforeHeight);

            g2.setColor(afterFill);
            g2.fillRect(afterX, baseline - afterHeight, barWidth, afterHeight);

            g2.setColor(JBColor.GRAY.darker());
            g2.setStroke(new BasicStroke(1f));
            g2.drawRect(beforeX, baseline - beforeHeight, barWidth, beforeHeight);
            g2.drawRect(afterX, baseline - afterHeight, barWidth, afterHeight);
        }
    }
}
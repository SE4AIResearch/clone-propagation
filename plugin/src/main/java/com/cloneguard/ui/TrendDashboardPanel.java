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

        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> reload());
        topBar.add(refreshBtn, BorderLayout.EAST);

        root.add(topBar, BorderLayout.NORTH);

        centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setBorder(new EmptyBorder(8, 12, 8, 12));

        JLabel chartLabel = new JLabel("Lines of code — before vs after each session");
        chartLabel.setFont(chartLabel.getFont().deriveFont(Font.BOLD, 12f));
        chartLabel.setBorder(new EmptyBorder(0, 0, 6, 0));
        chartLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        centerPanel.add(chartLabel);

        chartPanel = new ChartPanel();
        chartPanel.setPreferredSize(new Dimension(400, 220));
        chartPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 220));
        chartPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        centerPanel.add(chartPanel);

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

        // NEW: Understand-derived OO metrics section (WMC, CBO, DIT,
        // NOC), plus a status label that's shown instead of the chips
        // whenever the most recent session didn't have Understand data.
        JLabel understandLabel = new JLabel("Understand metrics (latest session)");
        understandLabel.setFont(understandLabel.getFont().deriveFont(Font.BOLD, 12f));
        understandLabel.setBorder(new EmptyBorder(0, 0, 6, 0));
        understandLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        centerPanel.add(understandLabel);

        understandStatusLabel = new JLabel(" ");
        understandStatusLabel.setForeground(JBColor.GRAY);
        understandStatusLabel.setFont(understandStatusLabel.getFont().deriveFont(Font.ITALIC, 12f));
        understandStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        centerPanel.add(understandStatusLabel);

        understandMetricsContainer = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        understandMetricsContainer.setAlignmentX(Component.LEFT_ALIGNMENT);
        understandMetricsContainer.setOpaque(false);
        centerPanel.add(understandMetricsContainer);

        // NEW: always-visible legend spelling out each metric's full name
        // and a one-line explanation -- the chips above stay short (CC,
        // WMC, LOC...) so the row itself stays compact and readable, but
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
        RefactorSession latest = sessions.get(sessions.size() - 1);
        understandMetricsContainer.removeAll();
        if (latest.understandAvailable) {
            understandStatusLabel.setText(" ");
            // Each chip below gets a short one-line tooltip explaining
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
        JPanel chip = new JPanel();
        chip.setLayout(new BoxLayout(chip, BoxLayout.Y_AXIS));
        chip.setOpaque(false);
        chip.setToolTipText(tooltip);

        JLabel nameLabel = new JLabel(label);
        nameLabel.setForeground(color);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 11f));
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        chip.add(nameLabel);

        MiniBarPair bars = new MiniBarPair(before, after, color);
        bars.setAlignmentX(Component.LEFT_ALIGNMENT);
        bars.setToolTipText(tooltip);
        chip.add(bars);

        JLabel valueLabel = new JLabel(before + " \u2192 " + after);
        valueLabel.setForeground(JBColor.GRAY);
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.PLAIN, 10f));
        valueLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        chip.add(valueLabel);

        return chip;
    }

    /**
     * Tiny custom-painted paired-bar widget: "before" (muted gray) next
     * to "after" (the metric's own color), each scaled against this
     * metric's own before/after max -- deliberately NOT scaled against
     * the other metrics, since CC and CBO can differ by an order of
     * magnitude and forcing one shared scale would make the smaller
     * metric's bars unreadably thin. This is a fixed-size decoration
     * alongside the numeric label, not a general chart component --
     * matches the scope of the existing LOC chart above, which is also
     * explicitly documented as deliberately simple rather than a
     * general-purpose charting widget.
     */
    private static class MiniBarPair extends JPanel {
        private final int before;
        private final int after;
        private final Color afterColor;
        private static final int WIDTH = 56;
        private static final int HEIGHT = 30;
        private static final int BAR_W = 14;

        MiniBarPair(int before, int after, Color afterColor) {
            this.before = before;
            this.after = after;
            this.afterColor = afterColor;
            setOpaque(false);
            Dimension size = new Dimension(WIDTH, HEIGHT);
            setPreferredSize(size);
            setMaximumSize(size);
            setMinimumSize(size);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int max = Math.max(1, Math.max(before, after));
            int baseline = HEIGHT - 3;
            int usableHeight = HEIGHT - 8;
            int beforeH = (int) Math.round((before / (double) max) * usableHeight);
            int afterH = (int) Math.round((after / (double) max) * usableHeight);

            int beforeX = 4;
            int afterX = beforeX + BAR_W + 6;

            g2.setColor(JBColor.GRAY);
            g2.fillRect(beforeX, baseline - beforeH, BAR_W, Math.max(1, beforeH));
            g2.setColor(afterColor);
            g2.fillRect(afterX, baseline - afterH, BAR_W, Math.max(1, afterH));

            g2.setColor(JBColor.border());
            g2.drawLine(0, baseline, WIDTH, baseline);

            g2.dispose();
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
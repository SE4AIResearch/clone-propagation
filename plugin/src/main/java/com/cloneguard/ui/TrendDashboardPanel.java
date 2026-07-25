package com.cloneguard.ui;

import com.cloneguard.model.RefactorSession;
import com.cloneguard.services.MetricsTrackerService;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.Ellipse2D;
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
 */
public class TrendDashboardPanel {

    private final Project project;
    private final JPanel root;
    private final JLabel fileLabel;
    private final JLabel summaryLabel;
    private final ChartPanel chartPanel;
    private final JPanel breakdownContainer;
    private final JPanel cloneTypeBreakdownContainer;

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

        JPanel centerPanel = new JPanel();
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
            totalNetComplexity += s.netComplexityChanged();
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

        // Same safety net as the empty-state branch above — guarantees
        // the chart and every other child actually repaints, regardless
        // of whether this ran from the Refresh button or the automatic
        // post-scan call.
        root.revalidate();
        root.repaint();
    }

    private JLabel breakdownChip(String label, int count, Color color) {
        JLabel chip = new JLabel("\u25CF " + label + " " + count);
        chip.setForeground(color);
        chip.setFont(chip.getFont().deriveFont(Font.PLAIN, 12f));
        return chip;
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
            int padLeft = 40, padRight = 16, padTop = 12, padBottom = 24;

            g2.setColor(JBColor.background().brighter());
            g2.fillRect(0, 0, w, h);

            if (sessions.isEmpty()) {
                g2.setColor(JBColor.GRAY);
                g2.drawString("No data yet", padLeft, h / 2);
                g2.dispose();
                return;
            }

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

            drawLine(g2, sessions, xs, padTop, chartH, maxLoc, true, JBColor.GRAY);
            drawLine(g2, sessions, xs, padTop, chartH, maxLoc, false,
                    new JBColor(new Color(46, 160, 90), new Color(90, 200, 130)));

            g2.setColor(JBColor.GRAY);
            int labelStep = Math.max(1, n / 8);
            for (int i = 0; i < n; i += labelStep) {
                g2.drawString("S" + (i + 1), xs[i] - 8, h - 6);
            }

            g2.dispose();
        }

        private void drawLine(Graphics2D g2, List<RefactorSession> sessions, int[] xs,
                               int padTop, int chartH, int maxLoc, boolean before, Color color) {
            int n = sessions.size();
            int[] ys = new int[n];
            for (int i = 0; i < n; i++) {
                int value = before ? sessions.get(i).locBefore : sessions.get(i).locAfter;
                ys[i] = padTop + chartH - (int) ((long) chartH * value / maxLoc);
            }

            g2.setColor(color);
            g2.setStroke(before
                    ? new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{4, 4}, 0)
                    : new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            for (int i = 0; i < n - 1; i++) {
                g2.drawLine(xs[i], ys[i], xs[i + 1], ys[i + 1]);
            }
            for (int i = 0; i < n; i++) {
                g2.fill(new Ellipse2D.Double(xs[i] - 3, ys[i] - 3, 6, 6));
            }
        }
    }
}
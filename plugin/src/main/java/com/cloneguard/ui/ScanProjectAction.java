package com.cloneguard.ui;

import com.cloneguard.model.CloneGroup;
import com.cloneguard.model.PushDownCandidate;
import com.cloneguard.services.FileScannerService;
import com.cloneguard.services.MetricsTrackerService;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * NEW (project-wide scan support, professor-requested): the "Scan Entire
 * Project" counterpart to ScanFileAction. Where ScanFileAction operates
 * on a single open file, this scans EVERY Java file in the project
 * together in one action, rather than requiring the user to repeat
 * "Scan Current File" once per file to get an equivalent picture.
 *
 * SCOPE NOTE: this deliberately does NOT attempt to redesign the
 * existing single-file results panel (CloneGuardToolWindowFactory
 * .showResults()) into a multi-file view -- that panel's Refactor/Pull
 * Up/Push Down buttons are built around acting on ONE currently-open
 * file's PSI, and turning that into a genuine multi-file results UI
 * (with per-file grouping, and refactor actions that correctly target
 * whichever file a given clone group actually came from) is a real,
 * separate UI design task in its own right, not something to fold into
 * this change silently. Instead, this action:
 *   1. Scans every file, showing a per-file clone-count summary
 *   2. Starts (and correctly finalizes, via the existing single-active-
 *      -session design already in MetricsTrackerService) a real session
 *      for EACH file in turn, so metrics tracking behaves exactly as it
 *      already does for Scan Current File -- no special-casing needed
 *      there at all
 *   3. Switches the Trend Dashboard to the new "Whole Project (Average)"
 *      view automatically, since that's the view this action's results
 *      are actually meant to feed
 *
 * For detailed, individually-actionable results on a SPECIFIC file
 * found to have clones here, the existing "Scan Current File" action on
 * that file still works exactly as before.
 */
public class ScanProjectAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(ScanProjectAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        FileScannerService scanner = project.getService(FileScannerService.class);
        if (scanner == null) {
            Messages.showInfoMessage(project, "Scanner service unavailable.", "CloneGuard");
            return;
        }

        ProgressManager.getInstance().run(
            new Task.Backgroundable(project, "CloneGuard: Scanning entire project...", false) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(false);

                    // scanProject() itself does the file discovery and
                    // per-file scanFile()+findPushDownCandidates() calls
                    // (see FileScannerService). Wrapped in ReadAction
                    // since it walks PSI to find and read every Java file
                    // in the project.
                    Map<PsiFile, FileScannerService.FileScanResult> results =
                            ReadAction.compute(scanner::scanProject);

                    int totalFiles = results.size();
                    int totalClones = 0;
                    int totalPushDown = 0;
                    StringBuilder summary = new StringBuilder();
                    summary.append("Project scan complete — ").append(totalFiles).append(" file(s) scanned.\n\n");

                    int fileIndex = 0;
                    MetricsTrackerService tracker = MetricsTrackerService.getInstance(project);
                    for (Map.Entry<PsiFile, FileScannerService.FileScanResult> entry : results.entrySet()) {
                        fileIndex++;
                        PsiFile psiFile = entry.getKey();
                        List<CloneGroup> groups = entry.getValue().cloneGroups;
                        List<PushDownCandidate> pushDownCandidates = entry.getValue().pushDownCandidates;
                        totalClones += groups.size();
                        totalPushDown += pushDownCandidates.size();

                        indicator.setText("Recording session for " + psiFile.getName()
                                + " (" + fileIndex + "/" + totalFiles + ")...");
                        indicator.setFraction(fileIndex / (double) totalFiles);

                        // Calling startSession() once per file here relies
                        // entirely on MetricsTrackerService's EXISTING
                        // single-active-session design: each call below
                        // finalizes whichever file's session was
                        // previously open (if the user applied any
                        // refactors to it) before starting fresh for this
                        // file -- exactly the same behavior Scan Current
                        // File already produces, just looped across every
                        // file in the project instead of one at a time by
                        // hand. No changes to that session logic itself
                        // were needed to make this work correctly.
                        tracker.startSession(psiFile);

                        if (!groups.isEmpty()) {
                            summary.append("  ").append(psiFile.getName())
                                    .append(": ").append(groups.size()).append(" clone group(s)\n");
                        }
                        if (!pushDownCandidates.isEmpty()) {
                            summary.append("  ").append(psiFile.getName())
                                    .append(": ").append(pushDownCandidates.size()).append(" push-down candidate(s)\n");
                        }
                    }

                    // FIX (code review, professor-flagged, confirmed
                    // valid): startSession() only finalizes the
                    // PREVIOUS file's session as a side effect of
                    // starting the NEXT one -- exactly as noted in the
                    // comment above, that's intentional and correct for
                    // every file except the very LAST one in this loop.
                    // With nothing calling startSession() again
                    // afterward, the last file's session was left open
                    // and unfinalized -- any refactors a user later
                    // applied to that specific file (without an
                    // intervening scan of some OTHER file first) would
                    // silently never get recorded. Finalizing explicitly
                    // here closes that gap without changing the
                    // per-file behavior above at all.
                    tracker.finalizeCurrentSessionIfDirty();

                    if (totalClones == 0 && totalPushDown == 0) {
                        summary.append("No clones or push-down candidates found across the project.");
                    } else {
                        summary.append("\n").append(totalClones).append(" total clone group(s), ")
                                .append(totalPushDown).append(" total push-down candidate(s) found. "
                                        + "Open each file individually via Scan Current File for detailed, actionable results.");
                    }

                    LOG.info("[CloneGuard] Project scan complete: " + totalFiles + " file(s), "
                            + totalClones + " total clone group(s), " + totalPushDown + " total push-down candidate(s)");

                    final String finalSummary = summary.toString();
                    ApplicationManager.getApplication().invokeLater(() -> {
                        Messages.showInfoMessage(project, finalSummary, "CloneGuard — Project Scan");
                        CloneGuardToolWindowFactory.refreshTrendDashboardProjectAverage(project);
                    });
                }
            }
        );
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
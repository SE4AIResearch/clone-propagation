package com.cloneguard.ui;

import com.cloneguard.model.CloneGroup;
import com.cloneguard.model.PushDownCandidate;
import com.cloneguard.services.FileScannerService;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class ScanFileAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(ScanFileAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        VirtualFile[] files = FileEditorManager.getInstance(project).getSelectedFiles();
        if (files.length == 0) {
            Messages.showInfoMessage(project, "No file open in editor.", "CloneGuard");
            return;
        }

        PsiFile psiFile = PsiManager.getInstance(project).findFile(files[0]);
        if (psiFile == null) {
            Messages.showInfoMessage(project, "Could not read file: " + files[0].getName(), "CloneGuard");
            return;
        }

        FileScannerService scanner = project.getService(FileScannerService.class);
        if (scanner == null) {
            Messages.showInfoMessage(project, "Scanner service unavailable.", "CloneGuard");
            return;
        }

        final PsiFile finalPsiFile = psiFile;

        // CHANGED (Understand integration): startSession() now shells
        // out to the `und` command-line tool via UnderstandMetricsService
        // -- a real create/add/analyze/metrics cycle that can take
        // several actual seconds, not the near-instant in-house PSI
        // traversal it used to be. It USED to be safe to call directly
        // here on the EDT (actionPerformed runs on the UI thread) since
        // it was effectively free; now doing that would freeze the
        // entire IDE for the duration of the Understand analysis on
        // every single scan. Moved inside the existing
        // Task.Backgroundable block below instead, alongside the other
        // genuinely slow work (scanner.scanFile, findPushDownCandidates)
        // that was already correctly backgrounded.
        //
        // The "need at least 2 functions" pre-check below still runs on
        // the EDT first, same as before -- extractFunctions() is cheap
        // PSI-only work, no reason to move it. Only startSession() itself
        // (and, correspondingly, the session-finalization work inside
        // it) moves into the background task.
        Map<String, String> functions = scanner.extractFunctions(psiFile);
        if (functions.size() < 2) {
            // Session boundary must still be recorded even when this
            // guard rejects the scan (see the original FIX note this
            // replaces) -- but startSession() itself is now potentially
            // slow, so this rejection path also needs to run it off the
            // EDT rather than block here waiting on Understand.
            ProgressManager.getInstance().run(
                new Task.Backgroundable(project, "CloneGuard: Recording session...", false) {
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) {
                        indicator.setIndeterminate(true);
                        com.cloneguard.services.MetricsTrackerService.getInstance(project).startSession(finalPsiFile);
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                            CloneGuardToolWindowFactory.refreshTrendDashboard(project, finalPsiFile.getName());
                            Messages.showInfoMessage(project,
                                    "Need at least 2 functions to scan for clones.\nFound: " + functions.size(),
                                    "CloneGuard");
                        });
                    }
                }
            );
            return;
        }

        ProgressManager.getInstance().run(
            new Task.Backgroundable(project, "CloneGuard: Scanning for clones...", false) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    indicator.setText("Recording session metrics (Understand)...");

                    // Moved here from the top of actionPerformed() -- see
                    // the comment above for why. Runs before the actual
                    // clone scan below, same ordering as originally
                    // intended, just now genuinely off the UI thread.
                    com.cloneguard.services.MetricsTrackerService.getInstance(project).startSession(finalPsiFile);

                    indicator.setText("Analysing " + finalPsiFile.getName() + "...");

                    // PSI access must be wrapped in ReadAction when on background thread
                    List<CloneGroup> groups = ReadAction.compute(() ->
                            scanner.scanFile(finalPsiFile)
                    );

                    // Push Down candidates are a separate, independent analysis
                    // from clone detection (no duplicated pair involved) — run
                    // it in its own ReadAction alongside the clone scan above.
                    List<PushDownCandidate> pushDownCandidates = ReadAction.compute(() ->
                            scanner.findPushDownCandidates(finalPsiFile)
                    );

                    LOG.info("[CloneGuard] Scan complete: " + groups.size() + " clone group(s), " +
                            pushDownCandidates.size() + " push-down candidate(s) found");

                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                        CloneGuardToolWindowFactory.showResults(project, groups, pushDownCandidates, finalPsiFile.getName());
                        CloneGuardToolWindowFactory.refreshTrendDashboard(project, finalPsiFile.getName());
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
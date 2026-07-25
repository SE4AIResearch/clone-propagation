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

        // FIX (found live, Trend Dashboard testing): this used to sit
        // AFTER the "need at least 2 functions" guard below. That meant a
        // scan attempt on a file that had just been reduced to a single
        // method BY a successful Pull Up -- exactly the case where a
        // duplicate pair was consolidated into one shared method -- would
        // hit that guard, return early, and never reach this call at all.
        // The session from the refactor that just happened would never
        // get finalized or written to the log, silently losing it.
        // Confirmed live: Pull Up succeeded, the file dropped to one
        // method, the next scan was correctly rejected by the guard, and
        // the Trend Dashboard still showed zero sessions afterward.
        //
        // A session boundary should be tied to the USER'S ACTION of
        // attempting a new scan, not to whether that scan happens to find
        // enough functions to proceed -- so this now fires unconditionally
        // at the top of every scan attempt, before any early return below.
        com.cloneguard.services.MetricsTrackerService.getInstance(project).startSession(psiFile);

        // Pre-check function count on EDT (we're already in read-safe context here)
        Map<String, String> functions = scanner.extractFunctions(psiFile);
        if (functions.size() < 2) {
            // The session above was just finalized regardless of whether
            // this scan can proceed — refresh the dashboard tab here too,
            // not just on the success path below, so the user doesn't
            // have to know to click Refresh manually after a scan that
            // happens to hit this guard.
            CloneGuardToolWindowFactory.refreshTrendDashboard(project, psiFile.getName());
            Messages.showInfoMessage(project,
                "Need at least 2 functions to scan for clones.\nFound: " + functions.size(),
                "CloneGuard");
            return;
        }

        final PsiFile finalPsiFile = psiFile;

        ProgressManager.getInstance().run(
            new Task.Backgroundable(project, "CloneGuard: Scanning for clones...", false) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
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
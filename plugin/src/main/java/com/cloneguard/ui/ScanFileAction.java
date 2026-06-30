package com.cloneguard.ui;

import com.cloneguard.model.CloneGroup;
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

        // Pre-check function count on EDT (we're already in read-safe context here)
        Map<String, String> functions = scanner.extractFunctions(psiFile);
        if (functions.size() < 2) {
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

                    LOG.info("[CloneGuard] Scan complete: " + groups.size() + " clone group(s) found");

                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() ->
                        CloneGuardToolWindowFactory.showResults(project, groups, finalPsiFile.getName())
                    );
                }
            }
        );
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
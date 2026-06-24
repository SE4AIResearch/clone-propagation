package com.cloneguard.ui;

import com.cloneguard.services.CloneIndexService;
import com.cloneguard.services.FileScannerService;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public class IndexFileAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        VirtualFile[] files = FileEditorManager.getInstance(project).getSelectedFiles();
        if (files.length == 0) return;

        PsiFile psiFile = PsiManager.getInstance(project).findFile(files[0]);
        if (psiFile == null) return;

        FileScannerService scanner = project.getService(FileScannerService.class);
        CloneIndexService  index   = CloneIndexService.getInstance();

        Map<String, String> functions = scanner.extractFunctions(psiFile);
        for (Map.Entry<String, String> entry : functions.entrySet()) {
            index.indexFunction(project, entry.getKey(), entry.getValue());
        }

        Messages.showInfoMessage(
                project,
                "Indexed " + functions.size() + " function(s) from " + psiFile.getName() + ".\n" +
                "Total indexed: " + index.indexedFunctionCount(),
                "CloneGuard — Indexed"
        );
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}

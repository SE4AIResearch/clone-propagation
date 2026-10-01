package com.cloneguard.ui;

import com.cloneguard.refactor.RealCompilationChecker;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * ISOLATED TEST ACTION — proves RealCompilationChecker actually works
 * (invokes the project's own configured JDK's javac directly on the
 * currently open file, captures the real exit code) before it's wired
 * into extract()/delegate()/pullUp()/pushDown() for real.
 *
 * Run this once on a file you know compiles cleanly (expect: "COMPILED
 * SUCCESSFULLY"), then deliberately break it (e.g. call a method that
 * doesn't exist) and run it again to confirm it correctly reports a
 * real failure too.
 *
 * Temporary: remove this class (and its <action> entry in plugin.xml)
 * once RealCompilationChecker has been proven and wired into the real
 * refactor flow.
 */
public class TestRealCompilationAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            Messages.showWarningDialog("No project open.", "CloneGuard — Test");
            return;
        }

        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            Messages.showWarningDialog("No file is open in the editor.", "CloneGuard — Test");
            return;
        }
        VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (vf == null) {
            Messages.showWarningDialog("Could not read the open file.", "CloneGuard — Test");
            return;
        }

        Messages.showInfoMessage(
                "Starting a real compile of " + vf.getName() + " using the project's own configured JDK.\n\n" +
                "This runs in the background — a result dialog will appear once it finishes.",
                "CloneGuard — Real Compilation Test Starting");

        RealCompilationChecker.compileFileAsync(project, vf).thenAccept(result -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                StringBuilder message = new StringBuilder();
                if (result.success) {
                    message.append("✅ COMPILED SUCCESSFULLY\n\n")
                           .append(vf.getName()).append(" compiled with zero errors, using the project's ")
                           .append("own configured JDK and module classpath.");
                } else {
                    message.append("❌ COMPILATION FAILED\n\n");
                    if (result.errorDetail != null) {
                        message.append(result.errorDetail).append("\n\n");
                    }
                    String output = result.output;
                    if (output != null && !output.isBlank()) {
                        String trimmed = output.length() > 2000
                                ? "...\n" + output.substring(output.length() - 2000)
                                : output;
                        message.append(trimmed);
                    }
                }
                Messages.showInfoMessage(message.toString(), "CloneGuard — Real Compilation Test Result");
            });
        });
    }
}
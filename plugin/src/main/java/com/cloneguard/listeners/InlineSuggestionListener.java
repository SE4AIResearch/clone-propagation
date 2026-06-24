package com.cloneguard.listeners;

import com.cloneguard.model.CloneResult;
import com.cloneguard.services.CloneIndexService;
import com.cloneguard.services.FileScannerService;
import com.cloneguard.ui.CloneWarningDialog;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;

public class InlineSuggestionListener implements EditorFactoryListener {

    private static final Logger LOG = Logger.getInstance(InlineSuggestionListener.class);

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "CloneGuard-Checker");
                t.setDaemon(true);
                return t;
            });

    private final Map<Editor, ScheduledFuture<?>> reindexJobs = new ConcurrentHashMap<>();
    private final Map<Editor, AtomicBoolean> skipNext = new ConcurrentHashMap<>();

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        Project project = editor.getProject();
        if (project == null) return;

        skipNext.put(editor, new AtomicBoolean(false));

        MessageBusConnection conn = project.getMessageBus().connect();
        conn.subscribe(FileDocumentManagerListener.TOPIC, new FileDocumentManagerListener() {
            @Override
            public void beforeDocumentSaving(@NotNull Document document) {
                if (document == editor.getDocument()) {
                    LOG.info("CloneGuard: file saved, reindexing...");
                    reindexNow(editor, project);
                }
            }
        });

        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent e) {
                String inserted = e.getNewFragment().toString();

                if (skipNext.getOrDefault(editor, new AtomicBoolean(false)).getAndSet(false)) {
                    return;
                }

                if (inserted.length() > 30 && looksLikeJavaMethod(inserted)) {
                    LOG.info("CloneGuard: Java method insertion detected, length=" + inserted.length());
                    handleInsertion(editor, project, inserted, e.getOffset());
                }
                // NOTE: Removed scheduleReindex here — only reindex on Cmd+S
            }
        });

        scheduleReindex(editor, project);
    }

    @Override
    public void editorReleased(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        ScheduledFuture<?> job = reindexJobs.remove(editor);
        if (job != null) job.cancel(false);
        skipNext.remove(editor);
    }

    private void handleInsertion(Editor editor, Project project, String inserted, int offset) {
        // Do NOT reindex before checking — we only want to compare against
        // functions that existed BEFORE this paste, not the pasted function itself.

        // ── KEY FIX: body-only paste ──────────────────────────────────────
        // Developer typed signature themselves, pasted only the body content.
        // Detect this case and combine signature + body so Layer 1 sees the
        // full method and can match via normalized identifier hash (Type 2).
        boolean isBodyOnly = !inserted.contains("public ") &&
                             !inserted.contains("private ") &&
                             !inserted.contains("protected ") &&
                             !inserted.contains("static ");

        String codeToCheck = inserted;
        if (isBodyOnly) {
            String docText = editor.getDocument().getText();
            String sig = findSignatureBeforeOffset(docText, offset);
            if (sig != null) {
                codeToCheck = sig + "\n" + inserted + (inserted.trim().endsWith("}") ? "" : "\n}");
                LOG.info("[CloneGuard] Body-only paste — combined with signature: " + sig.trim());
            }
        }

        CloneResult result = CloneIndexService.getInstance().detect(project, codeToCheck);
        LOG.info("CloneGuard: detection result: " + result);

        if (!result.isClone && !result.isAiGenerated) return;

        ApplicationManager.getApplication().invokeLater(() -> {
            ApplicationManager.getApplication().runWriteAction(() ->
                CommandProcessor.getInstance().executeCommand(project, () -> {
                    try {
                        editor.getDocument().deleteString(offset, offset + inserted.length());
                    } catch (Exception e) {
                        LOG.warn("CloneGuard undo: " + e.getMessage());
                    }
                }, "CloneGuard Undo", null)
            );

            CloneWarningDialog dialog = new CloneWarningDialog(project, result, inserted, editor);
            dialog.show();

            if (dialog.getExitCode() == CloneWarningDialog.EXIT_ACCEPT_ANYWAY) {
                skipNext.getOrDefault(editor, new AtomicBoolean(false)).set(true);
                ApplicationManager.getApplication().runWriteAction(() ->
                    CommandProcessor.getInstance().executeCommand(project, () ->
                        editor.getDocument().insertString(offset, inserted),
                        "CloneGuard Re-insert", null)
                );
            }
        });
    }

    private boolean looksLikeJavaMethod(String t) {
        if (t == null || t.length() < 30) return false;

        // Full method paste — has access modifier + signature + body
        boolean hasAccessModifier = t.contains("public ") || t.contains("private ") ||
                                    t.contains("protected ") || t.contains("static ");
        boolean hasMethodSignature = t.contains("(") && t.contains(")");
        boolean hasBody = t.contains("{") && t.contains("}");
        boolean hasReturn = t.contains("return ") || t.contains("void ");

        if (hasAccessModifier && hasMethodSignature && hasBody && hasReturn) {
            return true;
        }

        // Body-only paste starting with {
        boolean isBodyOnly = t.trim().startsWith("{") &&
                             t.contains("return ") &&
                             t.contains("}") &&
                             t.length() > 40;
        if (isBodyOnly) return true;

        // Inner body paste — developer typed signature, pasted just the body.
        // Accept anything with return + semicolon — we grab the signature from
        // the editor context in handleInsertion, so no need to be strict here.
        boolean isInnerBody = t.contains("return ") && t.contains(";") && t.length() > 20;
        return isInnerBody;
    }

    /**
     * Looks backwards in the document from the paste offset to find the
     * method signature the developer typed (e.g. "public static int myFunc(int[] data) {").
     * Returns the signature including the opening brace, or null if not found.
     */
    private String findSignatureBeforeOffset(String docText, int offset) {
        if (offset <= 0) return null;
        String before = docText.substring(0, offset);
        // Find the last opening brace before the paste point
        int bracePos = before.lastIndexOf('{');
        if (bracePos < 0) return null;
        // Get the line containing that brace
        int lineStart = before.lastIndexOf('\n', bracePos);
        if (lineStart < 0) lineStart = 0;
        String sigLine = before.substring(lineStart, bracePos + 1).trim();
        // Must look like a method signature
        boolean hasAccess = sigLine.contains("public ") || sigLine.contains("private ") ||
                            sigLine.contains("protected ") || sigLine.contains("static ");
        boolean hasParens = sigLine.contains("(") && sigLine.contains(")");
        if (hasAccess && hasParens) {
            LOG.info("[CloneGuard] Found signature: " + sigLine);
            return sigLine;
        }
        return null;
    }

    private void scheduleReindex(Editor editor, Project project) {
        ScheduledFuture<?> ex = reindexJobs.remove(editor);
        if (ex != null) ex.cancel(false);
        reindexJobs.put(editor, scheduler.schedule(() ->
            ApplicationManager.getApplication().runReadAction(() -> {
                try { indexFunctions(editor, project); }
                catch (Exception e) { LOG.warn("reindex: " + e.getMessage()); }
            }), 800, TimeUnit.MILLISECONDS));
    }

    private void reindexNow(Editor editor, Project project) {
        try {
            ApplicationManager.getApplication().runReadAction(() -> indexFunctions(editor, project));
        } catch (Exception e) { LOG.warn("reindexNow: " + e.getMessage()); }
    }

    private void indexFunctions(Editor editor, Project project) {
        VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (vf == null) return;
        PsiFile psi = PsiManager.getInstance(project).findFile(vf);
        if (psi == null) return;
        CloneIndexService idx = CloneIndexService.getInstance();

        // Always clear first — removes deleted functions from index
        idx.clearWithServer(project);

        try {
            PsiTreeUtil.findChildrenOfType(psi, PsiMethod.class)
                .forEach(method -> {
                    String fullMethod = method.getText();
                    if (fullMethod != null && fullMethod.length() > 20) {
                        idx.indexFunction(project, method.getName(), fullMethod);
                    }
                });
            LOG.info("CloneGuard: indexed methods from " + vf.getName());
        } catch (Exception e) {
            FileScannerService scanner = project.getService(FileScannerService.class);
            if (scanner != null) {
                Map<String, String> fns = scanner.extractFunctions(psi);
                for (Map.Entry<String, String> e2 : fns.entrySet())
                    idx.indexFunction(project, e2.getKey(), e2.getValue());
                LOG.info("CloneGuard: indexed " + fns.size() + " from " + vf.getName());
            }
        }
    }
}
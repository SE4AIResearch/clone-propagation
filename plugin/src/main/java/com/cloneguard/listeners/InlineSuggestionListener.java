package com.cloneguard.listeners;

import com.cloneguard.model.CloneResult;
import com.cloneguard.services.CloneIndexService;
import com.cloneguard.services.PythonServerClient;
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

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        Project project = editor.getProject();
        if (project == null) return;

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
    }

    private void handleInsertion(Editor editor, Project project, String inserted, int offset) {
        // Do NOT reindex before checking — we only want to compare against
        // functions that existed BEFORE this paste, not the pasted function itself.

        // ── body-only paste handling ────────────────────────────────────────
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

        // Always run AI detection independently — works even on empty index

        if (!result.isClone && !result.isAiGenerated) return;

        final CloneResult finalResult = result;
        ApplicationManager.getApplication().invokeLater(() -> {
            // ── FIX: show the dialog BEFORE touching the editor at all. ────
            // The old flow deleted the pasted text unconditionally before the
            // dialog even appeared, then only reinserted it on Accept Anyway —
            // which meant Dismiss silently removed the pasted code instead of
            // leaving it untouched (Test 8's expected behavior), and Accept
            // Anyway relied on a delete-then-reinsert dance (plus a skipNext
            // flag to avoid re-triggering this same listener) just to end up
            // exactly where it started. Now:
            //   - Dismiss: do nothing. Code stays exactly as pasted.
            //   - Accept Anyway: do nothing. Code stays exactly as pasted.
            //   - Use Existing Function: remove the duplicate paste (the
            //     existing function already covers this logic) and let the
            //     dialog's own button handler navigate to it.
            // This also eliminates the double-paste class of bug by
            // construction — there's no reinsert step left to double.
            CloneWarningDialog dialog = new CloneWarningDialog(project, finalResult, inserted, editor);
            dialog.show();

            if (dialog.getExitCode() == CloneWarningDialog.EXIT_USE_EXISTING) {
                int[] deleteRange = locateInsertedText(editor.getDocument(), inserted, offset);
                if (deleteRange != null) {
                    ApplicationManager.getApplication().runWriteAction(() ->
                        CommandProcessor.getInstance().executeCommand(project, () -> {
                            try {
                                editor.getDocument().deleteString(deleteRange[0], deleteRange[1]);
                            } catch (Exception e) {
                                LOG.warn("CloneGuard undo: " + e.getMessage());
                            }
                        }, "CloneGuard Remove Duplicate", null)
                    );
                } else {
                    LOG.warn("CloneGuard undo: could not locate inserted text to remove — document changed too much");
                }
            }
            // Dismiss and Accept Anyway intentionally fall through to nothing.
        });
    }

    /**
     * Locates the still-present inserted text in the document, returning its
     * current [start, end) offsets. Tries the original offset first (fast
     * path), then an exact substring search, then a WHITESPACE-TOLERANT
     * search — because IntelliJ's smart paste auto-reindents inserted code
     * immediately on paste, so the raw clipboard text captured by the
     * DocumentListener callback frequently no longer matches the document
     * byte-for-byte (different leading whitespace per line). Comparing on
     * normalized whitespace lets us still find and remove the right block.
     */
    private int[] locateInsertedText(Document document, String inserted, int originalOffset) {
        String fullText = document.getText();
        int docLength = fullText.length();

        // Fast path: original offset still valid and still contains the text
        int safeOffset = Math.min(Math.max(originalOffset, 0), docLength);
        int safeEnd = Math.min(safeOffset + inserted.length(), docLength);
        if (safeEnd - safeOffset == inserted.length()) {
            String candidate = fullText.substring(safeOffset, safeEnd);
            if (candidate.equals(inserted)) {
                return new int[]{safeOffset, safeEnd};
            }
        }

        // Exact substring search anywhere in the document
        int idx = fullText.indexOf(inserted);
        if (idx >= 0) {
            return new int[]{idx, idx + inserted.length()};
        }

        // Whitespace-tolerant search
        return findByNormalizedWhitespace(fullText, inserted, safeOffset);
    }

    /**
     * Finds `target` inside `haystack` by comparing whitespace-normalized
     * text, then maps the match back to real offsets in the original
     * (non-normalized) haystack so callers can still do an exact deleteString.
     */
    private int[] findByNormalizedWhitespace(String haystack, String target, int hintOffset) {
        String normTarget = target.replaceAll("\\s+", " ").trim();
        if (normTarget.isEmpty()) return null;

        StringBuilder norm = new StringBuilder();
        List<Integer> origIndex = new ArrayList<>();
        boolean lastWasSpace = true;
        for (int i = 0; i < haystack.length(); i++) {
            char c = haystack.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!lastWasSpace) {
                    norm.append(' ');
                    origIndex.add(i);
                    lastWasSpace = true;
                }
            } else {
                norm.append(c);
                origIndex.add(i);
                lastWasSpace = false;
            }
        }

        String normHaystack = norm.toString();
        int matchStart = normHaystack.indexOf(normTarget);
        if (matchStart < 0 || origIndex.isEmpty()) return null;

        int matchEndExclusive = matchStart + normTarget.length();
        if (matchEndExclusive > origIndex.size()) return null;

        int origStart = origIndex.get(matchStart);
        int origEnd = origIndex.get(matchEndExclusive - 1) + 1;

        return new int[]{origStart, origEnd};
    }

    private boolean looksLikeJavaMethod(String t) {
        if (t == null || t.length() < 30) return false;

        boolean hasAccessModifier = t.contains("public ") || t.contains("private ") ||
                                    t.contains("protected ") || t.contains("static ");
        boolean hasMethodSignature = t.contains("(") && t.contains(")");
        boolean hasBody = t.contains("{") && t.contains("}");
        boolean hasReturn = t.contains("return ") || t.contains("void ");

        if (hasAccessModifier && hasMethodSignature && hasBody && hasReturn) {
            return true;
        }

        boolean isBodyOnly = t.trim().startsWith("{") &&
                             t.contains("return ") &&
                             t.contains("}") &&
                             t.length() > 40;
        if (isBodyOnly) return true;

        boolean isInnerBody = t.contains("return ") && t.contains(";") && t.length() > 20;
        return isInnerBody;
    }

    /**
     * Looks backwards in the document from the paste offset to find the
     * method signature the developer typed.
     */
    private String findSignatureBeforeOffset(String docText, int offset) {
        if (offset <= 0) return null;
        String before = docText.substring(0, offset);
        int bracePos = before.lastIndexOf('{');
        if (bracePos < 0) return null;
        int lineStart = before.lastIndexOf('\n', bracePos);
        if (lineStart < 0) lineStart = 0;
        String sigLine = before.substring(lineStart, bracePos + 1).trim();
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

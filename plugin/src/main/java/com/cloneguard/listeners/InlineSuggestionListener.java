package com.cloneguard.listeners;

import com.cloneguard.model.CloneResult;
import com.cloneguard.model.CloneType;
import com.cloneguard.model.PushDownCandidate;
import com.cloneguard.refactor.ExtractMethodEngine;
import com.cloneguard.services.CloneIndexService;
import com.cloneguard.services.PythonServerClient;
import com.cloneguard.services.FileScannerService;
import com.cloneguard.ui.CloneWarningDialog;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
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

    // Dedup for Scenario 1's Push Down notifications -- keyed by file path
    // + method + superclass + target subclass, so the same candidate isn't
    // re-notified on every subsequent paste in the same file. Cleared
    // implicitly per-IDE-session (in-memory only); a candidate that gets
    // actually pushed down will naturally stop being found by the next
    // scan anyway, so there's no need to explicitly remove entries here.
    private final Set<String> shownPushDownCandidates = ConcurrentHashMap.newKeySet();

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
                // Ignore edits made by CloneGuard's own refactoring engine —
                // otherwise Extract Method's own writes get mistaken for a
                // human paste and trigger this same listener on ourselves.
                String currentCommand = CommandProcessor.getInstance().getCurrentCommandName();
                if (currentCommand != null && currentCommand.startsWith("CloneGuard")) {
                    return;
                }

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

        CloneResult result = CloneIndexService.getInstance(project).detect(project, codeToCheck);
        LOG.info("CloneGuard: detection result: " + result);

        // Always run AI detection independently — works even on empty index

        if (!result.isClone && !result.isAiGenerated) return;

        // FIX: extract the duplicate method's NAME synchronously, right now,
        // directly from the text we already captured — rather than looking
        // it up later via PSI at the paste OFFSET. Found directly from
        // hand-testing two pastes done in quick succession: the offset-based
        // lookup happens inside its own later-queued task, which can run
        // after enough delay (or after a second paste's own document
        // changes) that the offset no longer points to the right place,
        // producing "could not find one or both methods" even though the
        // method clearly existed. A name parsed straight from the captured
        // text can never go stale — it doesn't depend on the document's
        // state at all.
        final String duplicateMethodName = extractMethodNameFromCode(codeToCheck);

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
                // Duplicate is being deleted — nothing left to offer refactoring on.
            } else {
                // Dismiss or Accept Anyway: the duplicate code stays exactly
                // where it was pasted. Show a persistent notification with a
                // "Refactor" action, so the user can come back to it later
                // without being forced to decide right now.
                //
                // NOTE: this replaces an earlier gutter-icon (LineMarkerProvider)
                // approach that never worked reliably — after extensive
                // debugging, IntelliJ was confirmed to never invoke ANY custom
                // LineMarkerProvider in this environment, even a minimal,
                // unconditional test implementation. Notifications are a
                // simpler, more robust platform mechanism with no equivalent
                // extension-point fragility.
                showRefactorNotification(editor, duplicateMethodName, finalResult);
            }

            // EXTENDED (Scenario 1/3 parity request): Push Down doesn't
            // have a natural paste trigger the way clone detection does --
            // it's not about the PASTED code being a duplicate at all, it's
            // static analysis over the whole file looking for a method
            // that's misplaced too high in the class hierarchy. Rather than
            // scan the whole file on EVERY keystroke-adjacent paste (a real
            // performance concern for Layer 1's <10ms design budget — see
            // the paper), this deliberately only runs piggybacked on a
            // paste that ALREADY triggered the more expensive dialog-
            // showing path above, bounding the added cost to cases where
            // CloneGuard was already doing non-trivial work regardless.
            checkForNewPushDownCandidates(editor, project);
        });
    }

    /**
     * Runs FileScannerService.findPushDownCandidates() over the whole file
     * in the background (never on the EDT — a full-file reference search
     * is real work, unlike the paste-triggered clone check this piggybacks
     * on), and shows a separate notification for any candidate not already
     * shown this session. Mirrors showRefactorNotification()'s own
     * notification pattern, with its own "Push Down →" action wired to
     * ExtractMethodEngine.pushDown() the same way Scenario 2's tool window
     * button already does.
     */
    private void checkForNewPushDownCandidates(Editor editor, Project project) {
        VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (vf == null) return;

        scheduler.schedule(() -> {
            List<PushDownCandidate> candidates;
            try {
                candidates = ApplicationManager.getApplication().runReadAction(
                        (com.intellij.openapi.util.Computable<List<PushDownCandidate>>) () -> {
                            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                            if (psiFile == null) return java.util.Collections.emptyList();
                            FileScannerService scanner = project.getService(FileScannerService.class);
                            if (scanner == null) return java.util.Collections.emptyList();
                            return scanner.findPushDownCandidates(psiFile);
                        });
            } catch (Exception e) {
                LOG.warn("CloneGuard: push-down check failed: " + e.getMessage());
                return;
            }

            for (PushDownCandidate candidate : candidates) {
                String dedupKey = vf.getPath() + "::" + candidate.methodName + "::"
                        + candidate.superClassName + "::" + candidate.targetSubclassName;
                if (!shownPushDownCandidates.add(dedupKey)) {
                    continue; // already shown this session
                }
                ApplicationManager.getApplication().invokeLater(() ->
                        showPushDownNotification(project, vf, candidate));
            }
        }, 500, TimeUnit.MILLISECONDS);
    }

    /**
     * Same notification shape as showRefactorNotification(), for a single
     * Push Down candidate. Uses the explicit-VirtualFile overload of
     * pushDown() for the same reason Pull Up's routing does — the file
     * this candidate was found in isn't guaranteed to still be the
     * focused editor by the time the user clicks the action.
     */
    private void showPushDownNotification(Project project, VirtualFile vf, PushDownCandidate candidate) {
        Notification notification = new Notification(
                "CloneGuard",
                "CloneGuard — Push Down Opportunity",
                candidate.getSummary() + ". Consider moving it down.",
                NotificationType.INFORMATION
        );

        notification.addAction(new AnAction("Push Down \u2192") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                // FIX: startSessionIfNoneActive() now shells out to
                // Understand (`und`), which can take several real
                // seconds -- calling it directly here would block the
                // EDT for that whole duration, since actionPerformed()
                // IS the EDT. Moved into a background task; the actual
                // refactor call (needs the EDT for its confirm dialog)
                // runs afterward via invokeLater.
                com.intellij.openapi.progress.ProgressManager.getInstance().run(
                        new com.intellij.openapi.progress.Task.Backgroundable(project, "CloneGuard: Recording session...", false) {
                            @Override
                            public void run(@NotNull com.intellij.openapi.progress.ProgressIndicator indicator) {
                                indicator.setIndeterminate(true);
                                PsiFile psiFileForMetrics = ApplicationManager.getApplication().runReadAction(
                                        (com.intellij.openapi.util.Computable<PsiFile>) () ->
                                                PsiManager.getInstance(project).findFile(vf));
                                if (psiFileForMetrics != null) {
                                    com.cloneguard.services.MetricsTrackerService.getInstance(project)
                                            .startSessionIfNoneActive(psiFileForMetrics);
                                }
                                ApplicationManager.getApplication().invokeLater(() ->
                                        ExtractMethodEngine.getInstance(project).pushDown(
                                                vf, candidate.methodName, candidate.targetSubclassName,
                                                (updatedFile) -> notification.expire()));
                            }
                        }
                );
            }
        });

        notification.addAction(new AnAction("Dismiss") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                notification.expire();
            }
        });

        notification.notify(project);
        LOG.info("CloneGuard: push-down notification shown for " + candidate.getSummary());
    }

    /**
     * Shows a persistent notification offering to refactor the just-pasted
     * clone, with a direct "Refactor with Extract Method" action that calls
     * the same ExtractMethodEngine Scenario 2's tool window button uses.
     */
    private void showRefactorNotification(Editor editor, String duplicateName, CloneResult result) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Project project = editor.getProject();
            if (project == null) return;

            VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
            if (vf == null) return;

            if (duplicateName == null) {
                LOG.warn("CloneGuard notification: could not determine the pasted method's name from its text");
                return;
            }

            String rawMatchedFunction = result.matchedFunction;
            // FIX: confirmed directly from log output — Layer 2 (server)
            // detection results include literal "()" as part of the matched
            // function name (e.g. "sumPositives()"), while Layer 1 (local)
            // results don't (e.g. "computeMinMaxSum"). A real Java method
            // name never contains parentheses — PsiMethod.getName() always
            // returns just the bare name — so searching for "sumPositives()"
            // can never match anything, explaining exactly why every
            // server-detected clone failed with "could not find one or both
            // methods" while every local-detected one succeeded. Stripped
            // here rather than in the server-response parsing code itself,
            // since that source wasn't available to inspect directly.
            final String canonicalName = (rawMatchedFunction != null)
                    ? rawMatchedFunction.replaceAll("\\(\\)\\s*$", "").trim()
                    : null;
            String cloneTypeLabel = (result.cloneType != null) ? result.cloneType.label : "Clone";
            int similarityPercent = (int) Math.round(result.similarity * 100);

            Notification notification = new Notification(
                    "CloneGuard",
                    "CloneGuard — Possible Duplicate",
                    duplicateName + "() looks like a " + cloneTypeLabel + " of " + canonicalName
                            + "() (" + similarityPercent + "% similarity).",
                    NotificationType.WARNING
            );

            String actionLabel = (result.cloneType == CloneType.TYPE_4)
                    ? "Refactor with Method Delegation" : "Refactor with Extract Method";
            notification.addAction(new AnAction(actionLabel) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    // FIX (found live, this session -- Scenario 1 all-four-
                    // types test): Type 4 semantic clones share no literal
                    // code by definition, so Extract Method always correctly
                    // (if confusingly) says "nothing in common" for them --
                    // this notification action only ever called extract(),
                    // with no fallback, unlike Scenario 2's tool window,
                    // which was given a Delegate route earlier this session.
                    // Route the same way here for parity.
                    //
                    // EXTENDED (Scenario 1/3 parity request): for Type 1/2/3
                    // pairs, this now ALSO tries Pull Up first, before
                    // falling back to Extract Method -- exactly mirroring
                    // CloneGuardToolWindowFactory.triggerRefactor()'s same
                    // two-step routing in Scenario 2. Uses the explicit-
                    // VirtualFile overload (not the editor-focus-based one)
                    // since `vf` is the file that was actually pasted into
                    // at the time this notification was built, which isn't
                    // guaranteed to still be the focused editor by the time
                    // the user clicks this button -- notifications are
                    // asynchronous, and focus can shift in between.
                    // EXTENDED (Scenario 1 dashboard-coverage request):
                    // recordRefactor() below silently does nothing if no
                    // session is currently open -- correct behavior when
                    // called from a scan-triggered refactor, but wrong
                    // here: if the user pastes a clone and refactors it
                    // via THIS notification before ever running "Scan
                    // Current File" in this session, there was previously
                    // no baseline to attribute it to at all, and the
                    // refactor would succeed but never appear on the
                    // Trend Dashboard. Opens one now if (and only if)
                    // nothing is already active -- see
                    // startSessionIfNoneActive()'s own doc for why this
                    // is safe to call unconditionally without risk of
                    // discarding an in-progress session from an earlier
                    // scan.
                    // FIX: same threading issue and same fix as the
                    // Push Down notification action above -- see that
                    // comment for the full explanation.
                    com.intellij.openapi.progress.ProgressManager.getInstance().run(
                            new com.intellij.openapi.progress.Task.Backgroundable(project, "CloneGuard: Recording session...", false) {
                                @Override
                                public void run(@NotNull com.intellij.openapi.progress.ProgressIndicator indicator) {
                                    indicator.setIndeterminate(true);
                                    PsiFile psiFileForMetrics = ApplicationManager.getApplication().runReadAction(
                                            (com.intellij.openapi.util.Computable<PsiFile>) () ->
                                                    PsiManager.getInstance(project).findFile(vf));
                                    if (psiFileForMetrics != null) {
                                        com.cloneguard.services.MetricsTrackerService.getInstance(project)
                                                .startSessionIfNoneActive(psiFileForMetrics);
                                    }
                                    ApplicationManager.getApplication().invokeLater(() -> {
                                        ExtractMethodEngine engine = ExtractMethodEngine.getInstance(project);
                                        if (result.cloneType == CloneType.TYPE_4) {
                                            engine.delegate(
                                                    vf, canonicalName, duplicateName, cloneTypeLabel,
                                                    (updatedFile) -> notification.expire());
                                        } else {
                                            boolean handledAsPullUp = engine.tryPullUpIfApplicable(
                                                    vf, canonicalName, duplicateName, cloneTypeLabel,
                                                    (updatedFile) -> notification.expire());
                                            if (!handledAsPullUp) {
                                                engine.extract(
                                                        vf, canonicalName, duplicateName, cloneTypeLabel,
                                                        (updatedFile) -> notification.expire());
                                            }
                                        }
                                    });
                                }
                            }
                    );
                }
            });

            notification.addAction(new AnAction("Dismiss") {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    notification.expire();
                }
            });

            notification.notify(project);
            LOG.info("CloneGuard: refactor notification shown for " + duplicateName + "() vs " + canonicalName + "()");
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

    /**
     * Extracts a Java method's NAME directly from its text — the identifier
     * immediately before the opening "(" of its parameter list, which is
     * itself immediately preceded by a return type. Deliberately
     * text-based, not PSI/offset-based: this needs to work on code that
     * isn't even part of the document yet in any stable way (it's called
     * synchronously, right when a paste is detected, before any of the
     * later dialog/notification machinery runs) and must never depend on
     * document state that could shift by the time it's used.
     */
    private String extractMethodNameFromCode(String code) {
        // FIX (found live, Scenario 1 Type 1 test — sumValuesExact): this
        // mirrors the exact same bug already found and fixed server-side
        // in get_return_type_shared() (server.py) earlier this session.
        // This regex used to run unanchored over the ENTIRE pasted text.
        // That's unsound the moment the pasted method has no explicit
        // modifier (public/private/etc.) -- e.g. a package-private
        // "int sumValuesExact(int[] arr) {" -- because the leading
        // (?:modifiers|\s)+ group then has nothing valid to anchor on AT
        // the real signature, so find() skips straight past it and
        // instead matches the first coincidental "TYPE NAME(" shape found
        // ANYWHERE in the body.
        //
        // For gcdRecursive() this accidentally landed on its own
        // recursive call, return gcdRecursive(b, a % b);, and by pure
        // coincidence still extracted the right name -- which is exactly
        // why THAT notification worked while this one silently didn't.
        // sumValuesExact() has no recursive call to accidentally rescue
        // it, so no match was found anywhere at all: this method returned
        // null, and showRefactorNotification()'s
        // `if (duplicateName == null) return;` guard bailed out silently
        // -- no error, nothing visible, just a notification that never
        // appeared.
        //
        // Fix: isolate just the SIGNATURE portion -- everything up to the
        // first top-level "{" -- before running the regex at all, same
        // approach as the server-side fix. A Java method's signature can
        // never itself contain a literal "{", so this guarantees the
        // regex only ever sees the real signature, never anything from
        // inside the body. Combined with changing the leading modifiers
        // group from + to * (so it can validly match ZERO modifiers), a
        // package-private signature now matches correctly starting at its
        // true first character, instead of only ever being reachable --
        // by luck -- via a coincidental match somewhere in the body.
        int braceIdx = code.indexOf('{');
        String signatureOnly = (braceIdx != -1) ? code.substring(0, braceIdx) : code;

        java.util.regex.Matcher m = Pattern.compile(
                "(?:public|private|protected|static|final|\\s)*\\s*(?:\\w+(?:<[^>]*>)?(?:\\[\\])?)\\s+(\\w+)\\s*\\("
        ).matcher(signatureOnly);
        return m.find() ? m.group(1) : null;
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
        CloneIndexService idx = CloneIndexService.getInstance(project);

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
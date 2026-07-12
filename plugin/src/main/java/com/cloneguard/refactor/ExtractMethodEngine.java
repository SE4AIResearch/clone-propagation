package com.cloneguard.refactor;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Extract Method refactoring engine, shared by BOTH:
 *   - Scenario 2 (the "Extract →" button in the CloneGuard tool window)
 *   - Scenario 1 (the gutter icon that appears after a pasted clone is
 *     accepted or dismissed)
 *
 * This is deliberately a single, shared implementation rather than two
 * separate copies — every safety measure below was found and fixed through
 * extensive hand-testing over one long session, and duplicating this logic
 * a second time for Scenario 1 would mean re-discovering (and re-fixing)
 * every one of those bugs independently instead of automatically inheriting
 * the fixes.
 *
 * One engine instance exists per project (see getInstance()), so helper-name
 * and result-class-name collision tracking persists across BOTH scenarios
 * consistently, rather than being scoped to "the current scan session" in a
 * way that wouldn't make sense for paste-triggered Scenario 1 refactors.
 *
 * Dialogs here are parented to null (not a specific JPanel) deliberately —
 * Scenario 1 can trigger a refactor without the Scenario 2 tool window ever
 * having been opened, so there is no guaranteed panel to parent to.
 */
public class ExtractMethodEngine {

    private static final Map<Project, ExtractMethodEngine> INSTANCES = new ConcurrentHashMap<>();

    public static ExtractMethodEngine getInstance(Project project) {
        return INSTANCES.computeIfAbsent(project, ExtractMethodEngine::new);
    }

    private final Project project;

    // Tracks helper method names created by Extract Method. Prevents two
    // separate refactors (from either scenario) generating two helpers with
    // the same name.
    private final Set<String> generatedHelperNames = new HashSet<>();

    // Tracks result-holder class names generated for mixed-type multi-value
    // extraction (e.g. XxxResult), same collision-prevention purpose as
    // generatedHelperNames above.
    private final Set<String> generatedResultClassNames = new HashSet<>();

    // Tracks which canonical/duplicate PAIRS have RECENTLY been extracted,
    // with a timestamp — guards against extract() somehow firing twice for
    // the same click (its original, narrow purpose). Deliberately NOT a
    // permanent record: found directly from hand-testing — clearing a
    // file's content and pasting fresh test code with the same method
    // names later left every future genuine attempt silently blocked
    // forever, since a plain Set has no way to know the file's actual
    // content changed. A short expiry window (a few seconds) still catches
    // a genuine rapid double-click, without permanently remembering
    // "this pair name was extracted once" past the moment that's actually
    // still relevant.
    private final Map<String, Long> extractedPairs = new ConcurrentHashMap<>();
    private static final long EXTRACTED_PAIR_GUARD_MS = 3000;

    private ExtractMethodEngine(Project project) {
        this.project = project;
    }

    // ── FIX: every dialog in this engine used to call raw Swing's
    // JOptionPane directly. Found directly from hand-testing Scenario 1:
    // after one notification's abort dialog (e.g. Type 4's "Nothing to
    // Extract") was dismissed, every OTHER pending notification became
    // unresponsive — clicking their "Refactor" links did nothing at all.
    // JOptionPane is plain Swing, entirely outside IntelliJ's own window/
    // focus management — mixing it with IntelliJ's notification balloons
    // left the platform's own UI state confused after the raw dialog
    // closed. Routing every dialog through IntelliJ's own Messages class
    // instead keeps everything inside the platform's UI framework, which
    // is also simply the correct way to show dialogs from an IntelliJ
    // plugin in the first place — JOptionPane was never the right choice
    // here, just what the very first version of this engine happened to
    // use.
    private static void showDialog(Object message, String title, int messageType) {
        String text = String.valueOf(message);
        if (messageType == JOptionPane.ERROR_MESSAGE) {
            Messages.showErrorDialog(text, title);
        } else if (messageType == JOptionPane.WARNING_MESSAGE) {
            Messages.showWarningDialog(text, title);
        } else if (messageType == JOptionPane.INFORMATION_MESSAGE) {
            Messages.showInfoMessage(text, title);
        } else {
            Messages.showMessageDialog(text, title, Messages.getInformationIcon());
        }
    }

    private static class ExtractionPlan {
        boolean aborted;
        String abortTitle;
        String abortMessage;
        int abortMessageType;

        PsiMethod canonicalMethod;
        PsiMethod duplicateMethod;
        PsiClass psiClass;
        String finalHelperName;
        String confirmMessage;
        String helperText;
        String resultClassText;
        String resultClassName;
        String newCanonicalBodyText;
        String newDuplicateBodyText;

        static ExtractionPlan abort(String title, String message, int type) {
            ExtractionPlan p = new ExtractionPlan();
            p.aborted = true;
            p.abortTitle = title;
            p.abortMessage = message;
            p.abortMessageType = type;
            return p;
        }
    }

    public void extract(String canonical, String duplicate, String cloneTypeLabel, java.util.function.Consumer<PsiFile> onComplete) {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            showDialog(
                    "No file is open in the editor.",
                    "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }
        VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (vf == null) {
            showDialog(
                    "Could not read the open file. Make sure it is saved.",
                    "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }
        extract(vf, canonical, duplicate, cloneTypeLabel, onComplete);
    }

    // ── FIX (round 2): the first fix pinned down WHICH editor a
    // notification's refactor belongs to, by capturing the Editor object at
    // notification-creation time. That helped, but the Editor object
    // ITSELF isn't guaranteed to stay valid — found directly from
    // hand-testing the exact "switch away, then come back" scenario this
    // fix was built for: IntelliJ can dispose and later recreate an Editor
    // instance when focus changes, leaving an old captured reference stale
    // even though the FILE is still genuinely open. The actual extraction
    // logic never needed a live Editor at all — it only ever used one to
    // reach a PsiFile via its Document. Resolving the PsiFile directly from
    // a VirtualFile (which stays stable across editor lifecycle changes)
    // removes this dependency entirely.
    public void extract(VirtualFile targetFile, String canonical, String duplicate, String cloneTypeLabel, java.util.function.Consumer<PsiFile> onComplete) {
        // FIX: pairKey used to be built from method names ALONE, with no
        // file scoping at all. Found directly from hand-testing: reusing
        // the same method names (e.g. multiplyValues/computeProduct) across
        // two DIFFERENT files meant the second file's genuinely-new
        // extraction attempt was silently blocked — the tracker thought
        // "this pair was already extracted" based on an entirely different
        // file's earlier, unrelated success. Method names are not unique
        // across files; including the file's path in the key fixes this.
        String filePath = (targetFile != null) ? targetFile.getPath() : "";
        String pairKey = filePath + "::" + (canonical.compareTo(duplicate) < 0
                ? canonical + "||" + duplicate
                : duplicate + "||" + canonical);
        Long lastExtractedAt = extractedPairs.get(pairKey);
        if (lastExtractedAt != null && (System.currentTimeMillis() - lastExtractedAt) < EXTRACTED_PAIR_GUARD_MS) {
            return;
        }

        if (targetFile == null || !targetFile.isValid()) {
            showDialog(
                    "The target file is no longer available.",
                    "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // FIX (round 3): found directly from hand-testing two independent
        // notifications back to back — the FIRST extraction's write action
        // (inserting a new helper method) can leave IntelliJ's PSI tree not
        // fully "settled" by the time a SECOND, unrelated extraction reads
        // it shortly after, even though both operate on the same stable
        // VirtualFile. Explicitly committing the document first guarantees
        // we're always working from a fully flushed, up-to-date tree,
        // regardless of what other edits (ours or the user's) happened
        // moments earlier.
        PsiDocumentManager.getInstance(project).commitAllDocuments();

        PsiFile psiFile = PsiManager.getInstance(project).findFile(targetFile);
        if (psiFile == null) {
            showDialog(
                    "Could not read the target file. Make sure it is saved.",
                    "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // All PSI reads happen inside this ReadAction — analysis only, no
        // dialogs shown here.
        ExtractionPlan plan = ReadAction.compute(() -> buildExtractionPlan(psiFile, canonical, duplicate, cloneTypeLabel));

        if (plan.aborted) {
            showDialog( plan.abortMessage, plan.abortTitle, plan.abortMessageType);
            return;
        }

        int choice = Messages.showYesNoDialog(plan.confirmMessage, "CloneGuard — Confirm Refactor", Messages.getQuestionIcon());
        if (choice != Messages.YES) return;

        final boolean[] writeFailed = {false};
        final String[] writeFailureMessage = {null};

        WriteCommandAction.runWriteCommandAction(project, "CloneGuard Extract Method", null, () -> {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
            PsiMethod finalHelperMethod;
            PsiClass finalResultClass = null;
            PsiCodeBlock finalNewCanonicalBlock;
            PsiCodeBlock finalNewDuplicateBlock;
            try {
                finalHelperMethod = factory.createMethodFromText(plan.helperText, plan.canonicalMethod);
                if (plan.resultClassText != null) {
                    finalResultClass = factory.createClassFromText(plan.resultClassText, plan.canonicalMethod).getInnerClasses()[0];
                }
                finalNewCanonicalBlock = factory.createCodeBlockFromText(plan.newCanonicalBodyText, plan.canonicalMethod);
                finalNewDuplicateBlock = factory.createCodeBlockFromText(plan.newDuplicateBodyText, plan.duplicateMethod);
            } catch (Exception ex) {
                writeFailed[0] = true;
                writeFailureMessage[0] = ex.getMessage();
                return;
            }

            PsiCodeBlock oldCanonicalBody = plan.canonicalMethod.getBody();
            if (oldCanonicalBody != null) oldCanonicalBody.replace(finalNewCanonicalBlock);

            PsiCodeBlock oldDuplicateBody = plan.duplicateMethod.getBody();
            if (oldDuplicateBody != null) oldDuplicateBody.replace(finalNewDuplicateBlock);

            if (plan.psiClass != null) {
                PsiElement anchor = plan.psiClass.addAfter(finalHelperMethod, plan.canonicalMethod);
                if (finalResultClass != null) {
                    plan.psiClass.addAfter(finalResultClass, anchor);
                }
            }

            com.intellij.psi.codeStyle.CodeStyleManager csm = com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project);
            csm.reformat(plan.canonicalMethod);
            csm.reformat(plan.duplicateMethod);
            if (plan.psiClass != null) csm.reformat(plan.psiClass);
        });

        if (writeFailed[0]) {
            showDialog(
                    "CloneGuard's Extract Method refactoring failed a safety check and was NOT applied:\n\n" +
                    writeFailureMessage[0] + "\n\nYour file was not modified.",
                    "CloneGuard — Refactor Aborted", JOptionPane.ERROR_MESSAGE);
            return;
        }

        generatedHelperNames.add(plan.finalHelperName);
        if (plan.resultClassName != null) generatedResultClassNames.add(plan.resultClassName);
        extractedPairs.put(pairKey, System.currentTimeMillis());

        showDialog(
                "✅ Extract Method applied!\n\n" +
                "Created helper: " + plan.finalHelperName + "()\n" +
                canonical + "() and " + duplicate + "() both now call it.\n\n" +
                "Re-scanning the file now to refresh results...",
                "CloneGuard — Refactor Complete", JOptionPane.INFORMATION_MESSAGE);

        onComplete.accept(psiFile);
    }

    // ── Pure analysis phase — every PSI read Extract Method needs, called
    // from inside a ReadAction. Returns either an aborted plan (with the
    // dialog to show) or a ready-to-confirm plan. Shows NO dialogs itself.
    private ExtractionPlan buildExtractionPlan(PsiFile psiFile, String canonical, String duplicate, String cloneTypeLabel) {
        PsiMethod canonicalMethod = null;
        PsiMethod duplicateMethod = null;
        for (PsiMethod m : PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod.class)) {
            if (m.getName().equals(canonical)) canonicalMethod = m;
            if (m.getName().equals(duplicate)) duplicateMethod = m;
        }

        if (canonicalMethod == null || duplicateMethod == null) {
            return ExtractionPlan.abort("CloneGuard",
                    "Could not find one or both methods in the open file.",
                    JOptionPane.WARNING_MESSAGE);
        }

        // Safety measure #0: canonical and duplicate must belong to the same
        // class. Found directly from hand-testing Scenario 1: a paste that
        // lands just past a class's closing brace produces a method that
        // sits OUTSIDE any class — invalid Java. Without this check, the
        // engine would still find both methods by name anywhere in the
        // file, rewire the call sites, and insert the helper into
        // canonical's class — leaving the duplicate orphaned outside any
        // class, which doesn't compile. This is the single earliest point
        // to catch that: before any other analysis, PSI reads, or writes
        // happen.
        PsiClass canonicalClass = canonicalMethod.getContainingClass();
        PsiClass duplicateClass = duplicateMethod.getContainingClass();
        if (canonicalClass == null || duplicateClass == null || !canonicalClass.equals(duplicateClass)) {
            return ExtractionPlan.abort("CloneGuard — Cannot Extract Safely",
                    "CloneGuard cannot safely extract: " + canonical + "() and " + duplicate +
                    "() are not both inside the same class. This usually means one of them ended up " +
                    "in the wrong place — check that the pasted code landed inside the intended class's " +
                    "braces, not after them. No changes were made.",
                    JOptionPane.WARNING_MESSAGE);
        }

        PsiCodeBlock canonicalBody = canonicalMethod.getBody();
        PsiCodeBlock duplicateBody = duplicateMethod.getBody();
        if (canonicalBody == null || duplicateBody == null) {
            return ExtractionPlan.abort("CloneGuard",
                    "One of the methods has no body (e.g. abstract or interface method) — cannot extract.",
                    JOptionPane.WARNING_MESSAGE);
        }

        PsiStatement[] canonicalStmts = canonicalBody.getStatements();
        PsiStatement[] duplicateStmts = duplicateBody.getStatements();

        int[] run = findLongestCommonContiguousBlock(canonicalStmts, duplicateStmts);

        // Safety measure #3: nothing shared to extract (genuine Type 4 case,
        // OR canonical was already refactored elsewhere -- see below)
        if (run == null || run[2] == 0) {
            // FIX (found live, this session -- Scenario 1 all-four-types
            // test): a genuine Type 4 clone isn't the only way this branch
            // gets hit. If canonical's own body has ALREADY been rewritten
            // into a one-line delegation by a DIFFERENT, earlier refactor
            // (two independent pending notifications sharing the same
            // canonical, resolved out of order -- confirmed directly:
            // resolving Test B's Type 2 notification first rewrote
            // calcTotal() into "return coreCalcTotal(arr);" BEFORE Test A's
            // Type 1 notification for calcTotal()/calcTotalExact() was
            // acted on), canonical's CURRENT body has nothing in common
            // with duplicate's original body too -- but for a completely
            // different reason than Type 4, and "expected for Type 4
            // semantic clones" is actively misleading here since this pair
            // was genuinely Type 1. Detect the wrapper pattern and say what
            // actually happened instead of guessing at semantic clone-hood.
            String wrapperNote = "";
            if (canonicalStmts.length == 1) {
                String soleStmtText = canonicalStmts[0].getText();
                java.util.regex.Matcher wrapperMatch = Pattern.compile(
                        "\\b(core\\w+)\\s*\\(").matcher(soleStmtText);
                if (wrapperMatch.find()) {
                    String helperName = wrapperMatch.group(1);
                    wrapperNote = "\n\nNote: " + canonical + "() currently just calls " + helperName +
                            "() -- it looks like it was already refactored by a DIFFERENT, earlier " +
                            "clone resolution (e.g. another pending notification for the same method). " +
                            "If " + duplicate + "() should also share that logic, try re-scanning with " +
                            "the CloneGuard tool window instead, which re-checks against the current code.";
                }
            }
            return ExtractionPlan.abort("CloneGuard — Nothing to Extract",
                    "CloneGuard could not find any statements " + canonical + "() and " + duplicate +
                    "() actually have in common — their implementations are completely different " +
                    "(this is expected for Type 4 semantic clones, e.g. recursive vs. iterative)." +
                    wrapperNote + "\n\n" +
                    "Extract Method has nothing to extract here. No changes were made.",
                    JOptionPane.INFORMATION_MESSAGE);
        }

        int cStart = run[0], dStart = run[1], len = run[2];

        // Safety measure #4: don't guess at argument mapping across a
        // parameter-count mismatch
        PsiParameter[] canonicalParams = canonicalMethod.getParameterList().getParameters();
        PsiParameter[] duplicateParams = duplicateMethod.getParameterList().getParameters();
        if (canonicalParams.length != duplicateParams.length) {
            return ExtractionPlan.abort("CloneGuard — Cannot Extract Safely",
                    canonical + "() and " + duplicate + "() take a different number of parameters — " +
                    "CloneGuard will not guess how to map arguments between them. No changes were made.",
                    JOptionPane.WARNING_MESSAGE);
        }

        // Safety measure #5: the matched block might depend on a variable
        // declared BEFORE it. Rather than aborting, pass each one in as an
        // extra helper parameter — see findIncomingDependencies() for why
        // this is safe when the block already contains its own return.
        List<IncomingDependency> incomingDeps = findIncomingDependencies(canonicalStmts, cStart, len, canonicalParams);

        // Find duplicate's own name for each dependency, by the same
        // (statement, position) lookup already used for escaping variables.
        // If duplicate's corresponding statement doesn't match the shape we
        // expect (missing, wrong type, fewer elements), abort — safer than
        // guessing at a name that might not exist in duplicate's scope.
        List<String> incomingDepDuplicateNames = new ArrayList<>();
        for (IncomingDependency dep : incomingDeps) {
            if (dep.declStmtIndex < 0 || dep.declStmtIndex >= dStart) {
                return ExtractionPlan.abort("CloneGuard — Cannot Extract Safely",
                        "CloneGuard cannot safely extract: the shared code depends on \"" + dep.canonicalName +
                        "\", and CloneGuard could not find where duplicate's corresponding value comes from. " +
                        "No changes were made.",
                        JOptionPane.WARNING_MESSAGE);
            }
            PsiStatement dDeclStmt = duplicateStmts[dep.declStmtIndex];
            if (!(dDeclStmt instanceof PsiDeclarationStatement dDecl)) {
                return ExtractionPlan.abort("CloneGuard — Cannot Extract Safely",
                        "CloneGuard cannot safely extract: \"" + dep.canonicalName + "\" is declared before the " +
                        "shared block in " + canonical + "(), but the corresponding statement in " + duplicate +
                        "() isn't a matching declaration. No changes were made.",
                        JOptionPane.WARNING_MESSAGE);
            }
            PsiElement[] dEls = dDecl.getDeclaredElements();
            if (dep.declElementIndex < 0 || dep.declElementIndex >= dEls.length
                    || !(dEls[dep.declElementIndex] instanceof PsiLocalVariable dLv)
                    || !dLv.getType().equals(dep.type)) {
                return ExtractionPlan.abort("CloneGuard — Cannot Extract Safely",
                        "CloneGuard cannot safely extract: \"" + dep.canonicalName + "\" and its counterpart in " +
                        duplicate + "() don't share the same type. No changes were made.",
                        JOptionPane.WARNING_MESSAGE);
            }
            incomingDepDuplicateNames.add(dLv.getName());
        }

        // Safety measure #1: collision-proof helper naming
        String baseHelperName = "core" + capitalize(canonical);
        String helperName = baseHelperName;
        PsiClass psiClass = canonicalMethod.getContainingClass();
        int suffix = 2;
        while ((psiClass != null && psiClass.findMethodsByName(helperName, false).length > 0)
                || generatedHelperNames.contains(helperName)) {
            helperName = baseHelperName + suffix;
            suffix++;
        }
        final String finalHelperName = helperName;

        String confirmMessage =
                "CloneGuard found a " + cloneTypeLabel + ":\n\n" +
                "  Canonical:  " + canonical + "()\n" +
                "  Duplicate:  " + duplicate + "()\n\n" +
                "Proposed refactoring (Extract Method):\n" +
                "  • Extract " + len + " shared statement(s) into a new helper: " + finalHelperName + "()\n" +
                "  • " + canonical + "() and " + duplicate + "() will both call it\n" +
                "  • Any statements unique to either method are preserved\n\n" +
                "Apply this refactoring now?";

        // Build helper signature from canonical's own parameter names/types,
        // PLUS any incoming dependencies (min/max/label-style variables
        // needed inside the block but declared before it) appended after.
        String paramListText = Arrays.stream(canonicalParams)
                .map(p -> p.getType().getPresentableText() + " " + p.getName())
                .reduce((a, b) -> a + ", " + b).orElse("");
        String canonicalArgsText = Arrays.stream(canonicalParams)
                .map(PsiParameter::getName).reduce((a, b) -> a + ", " + b).orElse("");
        String duplicateArgsText = Arrays.stream(duplicateParams)
                .map(PsiParameter::getName).reduce((a, b) -> a + ", " + b).orElse("");

        for (int idx = 0; idx < incomingDeps.size(); idx++) {
            IncomingDependency dep = incomingDeps.get(idx);
            String depParam = dep.type.getPresentableText() + " " + dep.canonicalName;
            paramListText = paramListText.isEmpty() ? depParam : paramListText + ", " + depParam;
            canonicalArgsText = canonicalArgsText.isEmpty() ? dep.canonicalName : canonicalArgsText + ", " + dep.canonicalName;
            String dupDepName = incomingDepDuplicateNames.get(idx);
            duplicateArgsText = duplicateArgsText.isEmpty() ? dupDepName : duplicateArgsText + ", " + dupDepName;
        }

        boolean blockHasReturn = false;
        StringBuilder sharedText = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sharedText.append(canonicalStmts[cStart + i].getText()).append("\n");
            if (canonicalStmts[cStart + i] instanceof PsiReturnStatement) blockHasReturn = true;
        }

        // Safety measure #6: the block might contain a CONDITIONAL return
        // (e.g. "if (n <= 1) return n;" with no else) rather than a bare
        // top-level return statement. blockHasReturn above only recognizes
        // an actual PsiReturnStatement at the top of the block — it can't
        // tell that an if-statement WRAPPING a return still leaves a path
        // where nothing returns at all. Found directly from hand-testing
        // fibonacciRecursive()/fibonacciIterative(): their only shared
        // statement is exactly this kind of guard clause. Treating that as
        // "the block already returns, nothing more needed" and falling
        // back to the method's own return type produces a helper missing a
        // return on the non-guard path — a genuine "missing return
        // statement" compile error, not a warning. If a return exists
        // ANYWHERE in the block but the block doesn't unconditionally
        // return, there's no safe way to know what the helper should
        // return on every path — abort rather than guess.
        if (!blockHasReturn) {
            boolean blockContainsAnyReturn = false;
            for (int i = 0; i < len; i++) {
                if (!PsiTreeUtil.findChildrenOfType(canonicalStmts[cStart + i], PsiReturnStatement.class).isEmpty()) {
                    blockContainsAnyReturn = true;
                    break;
                }
            }
            if (blockContainsAnyReturn) {
                return ExtractionPlan.abort("CloneGuard — Cannot Extract Safely",
                        "CloneGuard cannot safely extract this: the shared code contains a conditional " +
                        "return (e.g. an early-exit guard clause) without a value guaranteed on every path. " +
                        "Extracting just this piece risks creating a helper that doesn't compile. " +
                        "No changes were made.",
                        JOptionPane.WARNING_MESSAGE);
            }
        }

        // ── Escaping-variable check ──────────────────────────────────────
        List<String> escapingVarNames = new ArrayList<>();
        List<PsiType> escapingVarTypes = new ArrayList<>();
        List<Integer> escapingVarStmtIndices = new ArrayList<>();
        List<Integer> escapingVarElementIndices = new ArrayList<>();

        if (!blockHasReturn) {
            for (int i = 0; i < len; i++) {
                PsiStatement stmt = canonicalStmts[cStart + i];
                if (stmt instanceof PsiDeclarationStatement decl) {
                    int elemIdx = 0;
                    for (PsiElement el : decl.getDeclaredElements()) {
                        if (el instanceof PsiLocalVariable lv) {
                            String varName = lv.getName();
                            boolean usedOutsideBlock = false;
                            for (int k = 0; k < canonicalStmts.length; k++) {
                                if (k >= cStart && k < cStart + len) continue; // inside the block itself
                                if (referencesIdentifier(canonicalStmts[k].getText(), varName)) {
                                    usedOutsideBlock = true;
                                    break;
                                }
                            }
                            if (usedOutsideBlock) {
                                escapingVarNames.add(varName);
                                escapingVarTypes.add(lv.getType());
                                escapingVarStmtIndices.add(i);
                                escapingVarElementIndices.add(elemIdx);
                            }
                        }
                        elemIdx++;
                    }
                }
            }

        }

        boolean multiEscape = escapingVarNames.size() > 1;
        boolean mixedTypes = false;
        if (multiEscape) {
            PsiType firstType = escapingVarTypes.get(0);
            for (PsiType t : escapingVarTypes) {
                if (!t.equals(firstType)) { mixedTypes = true; break; }
            }
        }
        String escapingVarName = escapingVarNames.isEmpty() ? null : escapingVarNames.get(0);
        PsiType escapingVarType = escapingVarTypes.isEmpty() ? null : escapingVarTypes.get(0);

        List<String> duplicateEscapingVarNames = new ArrayList<>();
        for (int n = 0; n < escapingVarStmtIndices.size(); n++) {
            int idx = escapingVarStmtIndices.get(n);
            int elemIdx = escapingVarElementIndices.get(n);
            String dupName = escapingVarNames.get(n); // fallback default: same name as canonical
            PsiStatement dStmt = duplicateStmts[dStart + idx];
            if (dStmt instanceof PsiDeclarationStatement dDecl) {
                PsiElement[] declaredEls = dDecl.getDeclaredElements();
                if (elemIdx < declaredEls.length && declaredEls[elemIdx] instanceof PsiLocalVariable lv) {
                    dupName = lv.getName();
                }
            }
            duplicateEscapingVarNames.add(dupName);
        }
        String duplicateEscapingVarName = duplicateEscapingVarNames.isEmpty() ? null : duplicateEscapingVarNames.get(0);

        // ── Mixed-type multi-escape: generate a small private static nested
        // "result holder" class instead of an array (an array requires one
        // uniform element type, which doesn't exist when e.g. a String
        // label and two ints all escape together, as in describeRange()/
        // describeSpan()). The helper returns an instance of it; each call
        // site destructures the fields back into its own locally-named
        // variables — same idea as the array path, just generalized to
        // hold mixed types.
        String resultClassName = null;
        String resultClassText = null;
        if (multiEscape && mixedTypes) {
            String baseClassName = capitalize(finalHelperName) + "Result";
            resultClassName = baseClassName;
            int classSuffix = 2;
            while ((psiClass != null && psiClass.findInnerClassByName(resultClassName, false) != null)
                    || generatedResultClassNames.contains(resultClassName)) {
                resultClassName = baseClassName + classSuffix;
                classSuffix++;
            }

            StringBuilder fields = new StringBuilder();
            StringBuilder ctorParams = new StringBuilder();
            StringBuilder ctorAssigns = new StringBuilder();
            for (int idx = 0; idx < escapingVarNames.size(); idx++) {
                String typeText = escapingVarTypes.get(idx).getPresentableText();
                String name = escapingVarNames.get(idx);
                fields.append("final ").append(typeText).append(" ").append(name).append(";\n");
                if (idx > 0) ctorParams.append(", ");
                ctorParams.append(typeText).append(" ").append(name);
                ctorAssigns.append("this.").append(name).append(" = ").append(name).append(";\n");
            }
            resultClassText = "private static class " + resultClassName + " {\n"
                    + fields
                    + resultClassName + "(" + ctorParams + ") {\n" + ctorAssigns + "}\n"
                    + "}";
        }

        String helperReturnTypeText;
        String arrayCaptureVarName = "extracted" + finalHelperName.substring(0, 1).toUpperCase() + finalHelperName.substring(1);

        if (multiEscape && mixedTypes) {
            helperReturnTypeText = resultClassName;
            sharedText.append("return new ").append(resultClassName).append("(")
                    .append(String.join(", ", escapingVarNames)).append(");\n");
        } else if (multiEscape) {
            helperReturnTypeText = escapingVarType.getPresentableText() + "[]";
            sharedText.append("return new ").append(escapingVarType.getPresentableText())
                    .append("[]{").append(String.join(", ", escapingVarNames)).append("};\n");
        } else if (escapingVarName != null && escapingVarType != null) {
            helperReturnTypeText = escapingVarType.getPresentableText();
            sharedText.append("return ").append(escapingVarName).append(";\n");
        } else {
            PsiType returnType = canonicalMethod.getReturnType();
            helperReturnTypeText = (returnType != null) ? returnType.getPresentableText() : "void";
        }

        String helperText = "private " + helperReturnTypeText + " " + finalHelperName + "(" + paramListText + ") {\n"
                + sharedText + "\n}";

        boolean canonicalNeedsCapture = (escapingVarName != null) &&
                (multiEscape || referencesIdentifierOutsideRange(canonicalStmts, cStart, len, escapingVarName));
        boolean duplicateNeedsCapture = (escapingVarName != null) &&
                (multiEscape || referencesIdentifierOutsideRange(duplicateStmts, dStart, len, duplicateEscapingVarName));

        String canonicalCallLine;
        if (blockHasReturn) {
            canonicalCallLine = "return " + finalHelperName + "(" + canonicalArgsText + ");";
        } else if (multiEscape && mixedTypes) {
            StringBuilder sb = new StringBuilder();
            sb.append(resultClassName).append(" ").append(arrayCaptureVarName)
                    .append(" = ").append(finalHelperName).append("(").append(canonicalArgsText).append(");\n");
            for (int idx = 0; idx < escapingVarNames.size(); idx++) {
                String typeText = escapingVarTypes.get(idx).getPresentableText();
                sb.append(typeText).append(" ").append(escapingVarNames.get(idx))
                        .append(" = ").append(arrayCaptureVarName).append(".").append(escapingVarNames.get(idx)).append(";\n");
            }
            canonicalCallLine = sb.toString().trim();
        } else if (multiEscape) {
            StringBuilder sb = new StringBuilder();
            sb.append(helperReturnTypeText).append(" ").append(arrayCaptureVarName)
                    .append(" = ").append(finalHelperName).append("(").append(canonicalArgsText).append(");\n");
            String elemType = escapingVarType.getPresentableText();
            for (int idx = 0; idx < escapingVarNames.size(); idx++) {
                sb.append(elemType).append(" ").append(escapingVarNames.get(idx))
                        .append(" = ").append(arrayCaptureVarName).append("[").append(idx).append("];\n");
            }
            canonicalCallLine = sb.toString().trim();
        } else if (canonicalNeedsCapture) {
            canonicalCallLine = helperReturnTypeText + " " + escapingVarName + " = "
                    + finalHelperName + "(" + canonicalArgsText + ");";
        } else {
            canonicalCallLine = finalHelperName + "(" + canonicalArgsText + ");";
        }

        String duplicateCallLine;
        if (blockHasReturn) {
            duplicateCallLine = "return " + finalHelperName + "(" + duplicateArgsText + ");";
        } else if (multiEscape && mixedTypes) {
            StringBuilder sb = new StringBuilder();
            sb.append(resultClassName).append(" ").append(arrayCaptureVarName)
                    .append(" = ").append(finalHelperName).append("(").append(duplicateArgsText).append(");\n");
            for (int idx = 0; idx < duplicateEscapingVarNames.size(); idx++) {
                String typeText = escapingVarTypes.get(idx).getPresentableText();
                sb.append(typeText).append(" ").append(duplicateEscapingVarNames.get(idx))
                        .append(" = ").append(arrayCaptureVarName).append(".").append(escapingVarNames.get(idx)).append(";\n");
            }
            duplicateCallLine = sb.toString().trim();
        } else if (multiEscape) {
            StringBuilder sb = new StringBuilder();
            sb.append(helperReturnTypeText).append(" ").append(arrayCaptureVarName)
                    .append(" = ").append(finalHelperName).append("(").append(duplicateArgsText).append(");\n");
            String elemType = escapingVarType.getPresentableText();
            for (int idx = 0; idx < duplicateEscapingVarNames.size(); idx++) {
                sb.append(elemType).append(" ").append(duplicateEscapingVarNames.get(idx))
                        .append(" = ").append(arrayCaptureVarName).append("[").append(idx).append("];\n");
            }
            duplicateCallLine = sb.toString().trim();
        } else if (duplicateNeedsCapture) {
            duplicateCallLine = helperReturnTypeText + " " + duplicateEscapingVarName + " = "
                    + finalHelperName + "(" + duplicateArgsText + ");";
        } else {
            duplicateCallLine = finalHelperName + "(" + duplicateArgsText + ");";
        }

        StringBuilder newCanonicalBodyText = new StringBuilder("{\n");
        for (int i = 0; i < cStart; i++) newCanonicalBodyText.append(canonicalStmts[i].getText()).append("\n");
        newCanonicalBodyText.append(canonicalCallLine).append("\n");
        for (int i = cStart + len; i < canonicalStmts.length; i++) newCanonicalBodyText.append(canonicalStmts[i].getText()).append("\n");
        newCanonicalBodyText.append("}");

        StringBuilder newDuplicateBodyText = new StringBuilder("{\n");
        for (int i = 0; i < dStart; i++) newDuplicateBodyText.append(duplicateStmts[i].getText()).append("\n");
        newDuplicateBodyText.append(duplicateCallLine).append("\n");
        for (int i = dStart + len; i < duplicateStmts.length; i++) newDuplicateBodyText.append(duplicateStmts[i].getText()).append("\n");
        newDuplicateBodyText.append("}");

        ExtractionPlan plan = new ExtractionPlan();
        plan.aborted = false;
        plan.canonicalMethod = canonicalMethod;
        plan.duplicateMethod = duplicateMethod;
        plan.psiClass = psiClass;
        plan.finalHelperName = finalHelperName;
        plan.confirmMessage = confirmMessage;
        plan.helperText = helperText;
        plan.resultClassText = resultClassText;
        plan.resultClassName = resultClassName;
        plan.newCanonicalBodyText = newCanonicalBodyText.toString();
        plan.newDuplicateBodyText = newDuplicateBodyText.toString();
        return plan;
    }

    // ── Does this statement's text reference a given identifier? ────────────
    // Word-boundary match so "result" doesn't accidentally match inside
    // "resultValue" or similar.
    private static boolean referencesIdentifier(String statementText, String identifier) {
        return Pattern.compile("\\b" + Pattern.quote(identifier) + "\\b").matcher(statementText).find();
    }

    // ── Does ANY statement outside [rangeStart, rangeStart+rangeLen) reference this identifier? ──
    private static boolean referencesIdentifierOutsideRange(PsiStatement[] stmts, int rangeStart, int rangeLen, String identifier) {
        for (int i = 0; i < stmts.length; i++) {
            if (i >= rangeStart && i < rangeStart + rangeLen) continue;
            if (referencesIdentifier(stmts[i].getText(), identifier)) return true;
        }
        return false;
    }

    // ── Holds one variable the extracted block depends on that was
    // declared BEFORE the block instead of inside it — e.g. min/max/label
    // in describeRange()/describeSpan(), initialized before the shared
    // loop+return and still needed inside it.
    private static class IncomingDependency {
        String canonicalName;
        PsiType type;
        int declStmtIndex = -1;   // which "before" statement declared it
        int declElementIndex = -1; // its position within that statement
    }

    // ── Finds every LOCAL VARIABLE or PARAMETER the block [rangeStart,
    // rangeStart+rangeLen) references that ISN'T declared inside the block
    // itself and ISN'T one of the method's own parameters.
    //
    // FIX: the original version aborted the whole refactor the moment it
    // found ONE such dependency. Found directly from hand-testing
    // describeRange()/describeSpan(): min, max, and label are all declared
    // right before the loop+return that got matched as the shared block —
    // genuinely needed inside it (min/max are mutated by it, label is read
    // by its own return statement), but not accessible to a new helper
    // method with no parameters for them. Since the matched block already
    // contains its OWN return statement here, the correct fix isn't to
    // somehow smuggle a value back OUT — it's simply to pass each
    // dependency IN as an extra parameter, using its current value at the
    // point the block begins (the original declaration statements stay
    // exactly where they are, untouched, immediately before the new call).
    // The helper mutates its own local copies during the loop and its
    // existing return statement produces the correct result — this is
    // just an ordinary "pass a value in, use it locally" function
    // parameter, nothing extra needed on the way out.
    private static List<IncomingDependency> findIncomingDependencies(PsiStatement[] stmts, int rangeStart, int rangeLen,
                                                                        PsiParameter[] params) {
        Set<PsiElement> locallyAvailable = new HashSet<>();
        locallyAvailable.addAll(Arrays.asList(params));
        for (int i = 0; i < rangeLen; i++) {
            PsiStatement stmt = stmts[rangeStart + i];
            if (stmt instanceof PsiDeclarationStatement decl) {
                locallyAvailable.addAll(Arrays.asList(decl.getDeclaredElements()));
            }
            if (stmt instanceof PsiForStatement forStmt) {
                PsiStatement init = forStmt.getInitialization();
                if (init instanceof PsiDeclarationStatement initDecl) {
                    locallyAvailable.addAll(Arrays.asList(initDecl.getDeclaredElements()));
                }
            }
            if (stmt instanceof PsiForeachStatement foreachStmt) {
                locallyAvailable.add(foreachStmt.getIterationParameter());
            }
        }

        List<IncomingDependency> result = new ArrayList<>();
        Set<PsiElement> seen = new HashSet<>();
        for (int i = 0; i < rangeLen; i++) {
            for (PsiReferenceExpression ref : PsiTreeUtil.findChildrenOfType(stmts[rangeStart + i], PsiReferenceExpression.class)) {
                PsiElement resolved = ref.resolve();
                if ((resolved instanceof PsiLocalVariable || resolved instanceof PsiParameter)
                        && !locallyAvailable.contains(resolved) && seen.add(resolved)) {
                    IncomingDependency dep = new IncomingDependency();
                    dep.canonicalName = ref.getReferenceName();
                    dep.type = (resolved instanceof PsiLocalVariable lv) ? lv.getType()
                              : ((PsiParameter) resolved).getType();
                    // Locate WHICH "before" statement declared it, and at
                    // what position, so duplicate's corresponding name can
                    // be found the same way escaping-variable names are.
                    for (int k = 0; k < rangeStart; k++) {
                        if (stmts[k] instanceof PsiDeclarationStatement d) {
                            PsiElement[] els = d.getDeclaredElements();
                            for (int e = 0; e < els.length; e++) {
                                if (els[e] == resolved) {
                                    dep.declStmtIndex = k;
                                    dep.declElementIndex = e;
                                }
                            }
                        }
                    }
                    result.add(dep);
                }
            }
        }
        return result;
    }

    // ── Method Delegation — the Type 4 counterpart to Extract Method ────────
    // Extract Method needs a literal shared statement block, which Type 4
    // clones (same intent, different implementation -- e.g. loop vs.
    // recursion) never have by definition. Delegation needs only a
    // compatible SIGNATURE (same param types in order, same return type),
    // not shared code -- so duplicate() can simply call canonical()
    // directly. No helper method, no shared block, canonical is left
    // completely untouched. Mirrors the same technique already built and
    // verified on the GitHub-bot side of CloneGuard (server.py's
    // generate_delegation_suggestion).
    public void delegate(String canonical, String duplicate, String cloneTypeLabel, java.util.function.Consumer<PsiFile> onComplete) {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            showDialog("No file is open in the editor.", "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }
        VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (vf == null) {
            showDialog("Could not read the open file. Make sure it is saved.", "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }
        delegate(vf, canonical, duplicate, cloneTypeLabel, onComplete);
    }

    public void delegate(VirtualFile targetFile, String canonical, String duplicate, String cloneTypeLabel, java.util.function.Consumer<PsiFile> onComplete) {
        String filePath = (targetFile != null) ? targetFile.getPath() : "";
        String pairKey = filePath + "::delegate::" + (canonical.compareTo(duplicate) < 0
                ? canonical + "||" + duplicate
                : duplicate + "||" + canonical);
        Long lastAt = extractedPairs.get(pairKey);
        if (lastAt != null && (System.currentTimeMillis() - lastAt) < EXTRACTED_PAIR_GUARD_MS) {
            return;
        }

        if (targetFile == null || !targetFile.isValid()) {
            showDialog("The target file is no longer available.", "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }

        PsiDocumentManager.getInstance(project).commitAllDocuments();

        PsiFile psiFile = PsiManager.getInstance(project).findFile(targetFile);
        if (psiFile == null) {
            showDialog("Could not read the target file. Make sure it is saved.", "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }

        DelegationPlan plan = ReadAction.compute(() -> buildDelegationPlan(psiFile, canonical, duplicate, cloneTypeLabel));

        if (plan.aborted) {
            showDialog(plan.abortMessage, plan.abortTitle, plan.abortMessageType);
            return;
        }

        int choice = Messages.showYesNoDialog(plan.confirmMessage, "CloneGuard — Confirm Refactor", Messages.getQuestionIcon());
        if (choice != Messages.YES) return;

        final boolean[] writeFailed = {false};
        final String[] writeFailureMessage = {null};

        WriteCommandAction.runWriteCommandAction(project, "CloneGuard Method Delegation", null, () -> {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
            PsiCodeBlock finalNewDuplicateBlock;
            try {
                finalNewDuplicateBlock = factory.createCodeBlockFromText(plan.newDuplicateBodyText, plan.duplicateMethod);
            } catch (Exception ex) {
                writeFailed[0] = true;
                writeFailureMessage[0] = ex.getMessage();
                return;
            }
            PsiCodeBlock oldDuplicateBody = plan.duplicateMethod.getBody();
            if (oldDuplicateBody != null) oldDuplicateBody.replace(finalNewDuplicateBlock);

            com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project).reformat(plan.duplicateMethod);
        });

        if (writeFailed[0]) {
            showDialog(
                    "CloneGuard's Method Delegation refactoring failed a safety check and was NOT applied:\n\n" +
                    writeFailureMessage[0] + "\n\nYour file was not modified.",
                    "CloneGuard — Refactor Aborted", JOptionPane.ERROR_MESSAGE);
            return;
        }

        extractedPairs.put(pairKey, System.currentTimeMillis());

        showDialog(
                "✅ Method Delegation applied!\n\n" +
                duplicate + "() now calls " + canonical + "() directly.\n" +
                canonical + "() was left unchanged.\n\n" +
                "Re-scanning the file now to refresh results...",
                "CloneGuard — Refactor Complete", JOptionPane.INFORMATION_MESSAGE);

        onComplete.accept(psiFile);
    }

    private static class DelegationPlan {
        boolean aborted;
        String abortTitle;
        String abortMessage;
        int abortMessageType;
        PsiMethod duplicateMethod;
        String confirmMessage;
        String newDuplicateBodyText;

        static DelegationPlan abort(String title, String message, int type) {
            DelegationPlan p = new DelegationPlan();
            p.aborted = true;
            p.abortTitle = title;
            p.abortMessage = message;
            p.abortMessageType = type;
            return p;
        }
    }

    private DelegationPlan buildDelegationPlan(PsiFile psiFile, String canonical, String duplicate, String cloneTypeLabel) {
        PsiMethod canonicalMethod = null;
        PsiMethod duplicateMethod = null;
        for (PsiMethod m : PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod.class)) {
            if (m.getName().equals(canonical)) canonicalMethod = m;
            if (m.getName().equals(duplicate)) duplicateMethod = m;
        }
        if (canonicalMethod == null || duplicateMethod == null) {
            return DelegationPlan.abort("CloneGuard",
                    "Could not find one or both methods (" + canonical + "(), " + duplicate + "()) in this file. " +
                    "The file may have changed since this result was shown — try re-scanning.",
                    JOptionPane.WARNING_MESSAGE);
        }

        PsiClass canonicalClass = canonicalMethod.getContainingClass();
        PsiClass duplicateClass = duplicateMethod.getContainingClass();
        if (canonicalClass == null || duplicateClass == null || !canonicalClass.equals(duplicateClass)) {
            return DelegationPlan.abort("CloneGuard",
                    canonical + "() and " + duplicate + "() are not both inside the same class. This usually " +
                    "means one of them ended up in the wrong place. No changes were made.",
                    JOptionPane.WARNING_MESSAGE);
        }

        // Signature compatibility: same param types (positionally) and the
        // same return type. Delegation doesn't need matching code, only a
        // matching contract.
        PsiParameter[] canonicalParams = canonicalMethod.getParameterList().getParameters();
        PsiParameter[] duplicateParams = duplicateMethod.getParameterList().getParameters();
        if (canonicalParams.length != duplicateParams.length) {
            return DelegationPlan.abort("CloneGuard — Cannot Delegate Safely",
                    canonical + "() and " + duplicate + "() take a different number of parameters — " +
                    "delegation needs a matching signature. No changes were made.",
                    JOptionPane.WARNING_MESSAGE);
        }
        for (int i = 0; i < canonicalParams.length; i++) {
            if (!canonicalParams[i].getType().equals(duplicateParams[i].getType())) {
                return DelegationPlan.abort("CloneGuard — Cannot Delegate Safely",
                        "Parameter " + (i + 1) + " type differs between " + canonical + "() and " + duplicate +
                        "() — delegation needs a matching signature. No changes were made.",
                        JOptionPane.WARNING_MESSAGE);
            }
        }
        PsiType canonicalReturn = canonicalMethod.getReturnType();
        PsiType duplicateReturn = duplicateMethod.getReturnType();
        if (canonicalReturn == null || duplicateReturn == null || !canonicalReturn.equals(duplicateReturn)) {
            return DelegationPlan.abort("CloneGuard — Cannot Delegate Safely",
                    "Return types differ between " + canonical + "() and " + duplicate + "() — " +
                    "delegation needs a matching signature. No changes were made.",
                    JOptionPane.WARNING_MESSAGE);
        }

        String callArgsText = Arrays.stream(duplicateParams)
                .map(PsiParameter::getName).reduce((a, b) -> a + ", " + b).orElse("");
        boolean isVoid = "void".equals(canonicalReturn.getPresentableText());
        String callLine = isVoid
                ? canonical + "(" + callArgsText + ");"
                : "return " + canonical + "(" + callArgsText + ");";

        String confirmMessage =
                "CloneGuard found a " + cloneTypeLabel + ":\n\n" +
                "  Canonical:  " + canonical + "()\n" +
                "  Duplicate:  " + duplicate + "()\n\n" +
                "No literal code is shared here (expected for Type 4 semantic clones — same intent, " +
                "different implementation), but both methods have a compatible signature.\n\n" +
                "Proposed refactoring (Method Delegation):\n" +
                "  • " + duplicate + "() will simply call " + canonical + "() directly\n" +
                "  • " + canonical + "() is left completely unchanged\n\n" +
                "Apply this refactoring now?";

        DelegationPlan plan = new DelegationPlan();
        plan.aborted = false;
        plan.duplicateMethod = duplicateMethod;
        plan.confirmMessage = confirmMessage;
        plan.newDuplicateBodyText = "{\n" + callLine + "\n}";
        return plan;
    }

    // ── Longest common CONTIGUOUS block of statements (windowed structural
    // equality) ──────────────────────────────────────────────────────────
    // Contiguous, not a general LCS, on purpose: extracting a non-contiguous
    // scattering of matched statements into one helper would silently
    // reorder code — safer to only extract a single unbroken shared block.
    //
    // FIX (found live, this session -- IDE Scenario 2 all-four-types test):
    // the previous version normalized EVERY statement in a method ONCE,
    // using a SINGLE identifier->placeholder map built by scanning from the
    // top of the method (VAR1, VAR2... in first-occurrence order), then
    // compared those pre-computed strings directly. A preceding, unrelated
    // statement -- e.g. maxValueSafe()'s leading "if (arr == null ...)
    // return -1;" guard clause, which maxValue() doesn't have -- shifts
    // every placeholder number that follows it, so an otherwise IDENTICAL
    // shared loop later in the method compared as different text and was
    // missed entirely. Confirmed directly: maxValue()/maxValueSafe() share
    // an identical loop, but "Nothing to Extract" fired anyway. This is the
    // exact same bug (and fix) already found and fixed server-side in
    // CloneGuard's Python GitHub-bot engine earlier this session.
    //
    // Fix: instead of one global mapping, build the identifier
    // correspondence FRESH within each candidate window as it's compared --
    // only the relative order WITHIN the compared block matters now, not
    // what happened to come before it in either method.
    //
    // ── Keywords/literals kept verbatim during tokenization, never treated
    // as a substitutable identifier.
    private static final Set<String> LCS_KEYWORDS = Set.of(
            "if", "else", "for", "while", "do", "switch", "case", "return", "break", "continue",
            "new", "this", "super", "true", "false", "null", "int", "long", "double", "float",
            "boolean", "char", "byte", "short", "void", "String", "instanceof", "throw", "try", "catch", "finally"
    );

    private int[] findLongestCommonContiguousBlock(PsiStatement[] a, PsiStatement[] b) {
        int bestLen = 0, bestI = -1, bestJ = -1;
        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j < b.length; j++) {
                int len = 0;
                while (i + len < a.length && j + len < b.length
                        && statementsStructurallyEqual(a, i, i + len + 1, b, j, j + len + 1)) {
                    len++;
                }
                if (len > bestLen) { bestLen = len; bestI = i; bestJ = j; }
            }
        }
        if (bestLen == 0) return null;
        return new int[]{bestI, bestJ, bestLen};
    }

    /** Checks whether a[aStart:aEnd] and b[bStart:bEnd] are structurally
     * identical -- keywords/literals/punctuation must match exactly,
     * identifiers may differ but must form a consistent one-to-one mapping
     * built fresh across just this window. */
    private boolean statementsStructurallyEqual(PsiStatement[] a, int aStart, int aEnd,
                                                 PsiStatement[] b, int bStart, int bEnd) {
        if (aEnd - aStart != bEnd - bStart) return false;
        Map<String, String> aToB = new LinkedHashMap<>();
        Map<String, String> bToA = new LinkedHashMap<>();
        for (int k = 0; k < aEnd - aStart; k++) {
            List<String[]> tokensA = tokenizeStatement(a[aStart + k].getText());
            List<String[]> tokensB = tokenizeStatement(b[bStart + k].getText());
            if (tokensA.size() != tokensB.size()) return false;
            for (int t = 0; t < tokensA.size(); t++) {
                String kindA = tokensA.get(t)[0], valA = tokensA.get(t)[1];
                String kindB = tokensB.get(t)[0], valB = tokensB.get(t)[1];
                if (!kindA.equals(kindB)) return false;
                if (kindA.equals("id")) {
                    String mapped = aToB.get(valA);
                    if (mapped != null) {
                        if (!mapped.equals(valB)) return false;
                    } else if (bToA.containsKey(valB)) {
                        return false;
                    } else {
                        aToB.put(valA, valB);
                        bToA.put(valB, valA);
                    }
                } else if (!valA.equals(valB)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Tokenize into (kind, value) pairs -- kind is "lit", "kw", "id", or
     * "punct". Literals compared verbatim (never substituted) so e.g.
     * "range" and "span" stay visibly different, same reasoning as the
     * original tokenizer this replaces. */
    private List<String[]> tokenizeStatement(String stmtText) {
        List<String[]> tokens = new ArrayList<>();
        Matcher m = Pattern.compile(
                "\"(?:[^\"\\\\]|\\\\.)*\"" +      // double-quoted string literal
                "|'(?:[^'\\\\]|\\\\.)*'" +          // char literal
                "|\\d+(?:\\.\\d+)?[lLfFdD]?" +       // numeric literal
                "|[A-Za-z_][A-Za-z0-9_]*" +          // identifier
                "|[^A-Za-z0-9_\\s]+" +                // operators/punctuation
                "|\\s+"                                // whitespace
        ).matcher(stmtText);
        while (m.find()) {
            String token = m.group();
            if (token.matches("\\s+")) continue;
            if (token.startsWith("\"") || token.startsWith("'")) {
                tokens.add(new String[]{"lit", token});
            } else if (token.matches("\\d+(?:\\.\\d+)?[lLfFdD]?")) {
                tokens.add(new String[]{"lit", token});
            } else if (LCS_KEYWORDS.contains(token)) {
                tokens.add(new String[]{"kw", token});
            } else if (token.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                tokens.add(new String[]{"id", token});
            } else {
                tokens.add(new String[]{"punct", token});
            }
        }
        return tokens;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

}
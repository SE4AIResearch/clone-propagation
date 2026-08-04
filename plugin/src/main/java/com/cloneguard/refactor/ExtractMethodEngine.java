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
    //
    // FIX (professor-flagged, 3.1 -- High): this engine is a project-level
    // singleton, and refactorings can genuinely fire concurrently -- a
    // background Scenario 1 paste-check completing on one thread while the
    // user clicks Extract in Scenario 2's tool window on another. A plain
    // HashSet has no thread-safety guarantee at all under concurrent
    // add()/contains() calls; worst case, two refactors racing each other
    // could both decide the same helper name is available, generating a
    // real naming collision in the actual source file, or the set's
    // internal structure could be corrupted entirely. ConcurrentHashMap's
    // key-set view gives the exact same Set<String> API this code already
    // uses everywhere, with genuine thread-safety underneath -- no call
    // site below needed to change at all.
    private final Set<String> generatedHelperNames = ConcurrentHashMap.newKeySet();

    // Tracks result-holder class names generated for mixed-type multi-value
    // extraction (e.g. XxxResult), same collision-prevention purpose --
    // and same concurrency risk -- as generatedHelperNames above.
    private final Set<String> generatedResultClassNames = ConcurrentHashMap.newKeySet();

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

    // FIX (found live, Pull Up Method testing): resolves a method name
    // against a PsiFile, supporting TWO formats. A bare simple name
    // ("describe") matches the first method found with that name --
    // exactly the original behavior, unchanged, and still correct for
    // every existing same-class Extract Method / Delegation flow, since a
    // name collision was never possible there before Pull Up existed. A
    // class-qualified name ("Dog.describe") matches only a method with
    // that simple name INSIDE that specific class. The qualified format
    // is what FileScannerService.extractFunctions() now produces, but
    // ONLY when a real collision exists in the file (see its own comment
    // for why) -- so this qualified branch only ever activates for the
    // rare case a bare-name lookup would otherwise have been ambiguous.
    // Every one of this engine's method-lookup loops (extract, delegate,
    // pullUp, pushDown, and the pullUp-applicability pre-check) goes
    // through this one shared resolver so a fix here fixes all of them
    // identically, rather than needing five separate edits kept in sync.
    private static PsiMethod resolveMethodByName(PsiFile psiFile, String name) {
        if (name == null) return null;
        String targetClass = null;
        String targetMethod = name;
        int dot = name.indexOf('.');
        if (dot > 0) {
            targetClass = name.substring(0, dot);
            targetMethod = name.substring(dot + 1);
        }
        for (PsiMethod m : PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod.class)) {
            if (!m.getName().equals(targetMethod)) continue;
            if (targetClass == null) return m;
            PsiClass owner = m.getContainingClass();
            if (owner != null && targetClass.equals(owner.getName())) return m;
        }
        return null;
    }

    /**
     * Resolves the (canonical, duplicate) PAIR together, handling the
     * case where both names are literally identical — which is the
     * COMMON case for a real clone (e.g. Dog.describe() and
     * Cat.describe() are both just named "describe", with nothing to
     * qualify them by if the caller only ever tracked bare names).
     * resolveMethodByName() alone can't disambiguate this: called twice
     * with the same bare name, it deterministically returns the SAME
     * first match both times, so canonical and duplicate silently
     * resolve to the IDENTICAL PsiMethod — which then makes every
     * "different classes" check downstream (Pull Up eligibility, plan-
     * building) incorrectly fail, since as far as those checks can
     * tell, there's only one method involved, not two.
     *
     * FIX (found live, this session — Scenario 1 Pull Up parity test):
     * confirmed via direct testing that Dog.describe() / Cat.describe()
     * — identical bare names, the single most common real-world shape
     * for a clone pair — silently fell back to plain Extract Method
     * instead of Pull Up, even though Dog and Cat both extend Animal.
     * Scenario 2's own extraction already dodges this by qualifying
     * colliding names as "ClassName.methodName" ahead of time (see
     * FileScannerService.extractFunctions()); Scenario 1's paste-
     * detection index has no equivalent qualification step, so this
     * needs to be handled here instead, at resolution time, and this
     * fix applies to every caller that resolves a (canonical, duplicate)
     * pair — not just Pull Up's routing check — since the same
     * ambiguity exists wherever two same-named methods are resolved
     * independently by name.
     *
     * When canonical and duplicate are genuinely different strings
     * (already unambiguous — different names, or one/both already
     * class-qualified), this resolves each independently exactly as
     * resolveMethodByName always did, with zero behavior change for
     * that case.
     *
     * When they're identical, finds every method in the file with that
     * name and, if at least two exist, returns the first two IN FILE
     * ORDER as [canonical, duplicate] — a deterministic, defensible
     * convention. This is safe specifically because every caller of
     * this method already operates in a context where a clone was
     * independently detected between exactly two occurrences — the
     * structural/eligibility checks that run immediately after this
     * still independently verify real safety regardless of which two
     * get picked here, so even in a rare 3+-occurrence case this can't
     * produce an unsafe result, at worst a differently-paired (but
     * still individually valid) match than the one originally intended.
     */
    private static PsiMethod[] resolveDistinctPairByName(PsiFile psiFile, String canonical, String duplicate) {
        if (canonical == null || duplicate == null) return null;
        if (!canonical.equals(duplicate)) {
            PsiMethod a = resolveMethodByName(psiFile, canonical);
            PsiMethod b = resolveMethodByName(psiFile, duplicate);
            return (a == null || b == null) ? null : new PsiMethod[]{a, b};
        }
        String targetClass = null;
        String targetMethod = canonical;
        int dot = canonical.indexOf('.');
        if (dot > 0) {
            targetClass = canonical.substring(0, dot);
            targetMethod = canonical.substring(dot + 1);
        }
        List<PsiMethod> matches = new ArrayList<>();
        for (PsiMethod m : PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod.class)) {
            if (!m.getName().equals(targetMethod)) continue;
            if (targetClass != null) {
                PsiClass owner = m.getContainingClass();
                if (owner == null || !targetClass.equals(owner.getName())) continue;
            }
            matches.add(m);
            if (matches.size() >= 2) break;
        }
        if (matches.size() < 2) return null;
        return new PsiMethod[]{matches.get(0), matches.get(1)};
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

        PsiFile psiFile = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(targetFile));
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

        // Captured BEFORE the write action runs, from data already sitting
        // in the plan — the duplicate's original body text (about to be
        // replaced) versus its planned replacement text — so the "lines
        // eliminated" figure reflects the true before/after, not a
        // post-hoc guess.
        int oldDuplicateLines = countLines(plan.duplicateMethod.getBody() != null ? plan.duplicateMethod.getBody().getText() : "");
        int newDuplicateLines = countLines(plan.newDuplicateBodyText);
        int duplicatedLinesEliminated = Math.max(0, oldDuplicateLines - newDuplicateLines);

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
        com.cloneguard.services.MetricsTrackerService.getInstance(project).recordRefactor("extract", duplicatedLinesEliminated, cloneTypeLabel);

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
        PsiMethod[] resolvedPair = resolveDistinctPairByName(psiFile, canonical, duplicate);
        PsiMethod canonicalMethod = resolvedPair != null ? resolvedPair[0] : null;
        PsiMethod duplicateMethod = resolvedPair != null ? resolvedPair[1] : null;

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

        // FIX (found live, this session -- Scenario 2 final check, post-
        // refactor rescan): confirmed via live evidence that a matched
        // block of length 1 was being extracted just like any other --
        // specifically, sumEvenNumbersLoop() and a newly-created helper
        // both happened to start with the identical single TRIVIAL
        // statement "int total = 0;", and this coincidence alone was
        // enough to trigger a nonsensical extraction linking two
        // completely unrelated functions.
        //
        // FIX (found live, this session, round 2 -- corrects an
        // over-broad first attempt): blocking EVERY length-1 match was
        // wrong -- confirmed live it also broke lastElement()/
        // lastElementSafe(), a genuinely real Type 3 match whose entire
        // shared logic legitimately IS a single statement
        // ("return arr[arr.length - 1];"). Block length alone can't
        // distinguish "coincidental trivial statement" from "real logic
        // that happens to be one line" -- same category of mistake made
        // several times tonight on the server side, just resurfacing
        // here. The actual distinguishing feature is TRIVIALITY of the
        // statement's content, not its count: only reject a length-1
        // match when that one statement is a bare declaration assigning
        // a plain literal (e.g. "int total = 0;", "boolean found = false;")
        // -- nothing there to genuinely "extract" as shared behavior.
        // A statement doing real work (array indexing, a computed
        // expression, a meaningful return) still counts even alone.
        if (run != null && run[2] == 1) {
            String soleMatchedStmtText = canonicalStmts[run[0]].getText().trim();
            boolean isTrivialLiteralDeclaration = Pattern.compile(
                    "^(?:int|long|double|float|boolean|char|byte|short|String)\\s+\\w+\\s*=\\s*" +
                    "(?:0|0\\.0|0f|0L|false|true|null|\"\")\\s*;?$"
            ).matcher(soleMatchedStmtText).matches();
            if (isTrivialLiteralDeclaration) {
                run = null;
            }
        }

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

        // FIX (code review, professor-flagged, confirmed valid): the
        // server used to identify a CloneGuard-generated wrapper purely
        // by checking whether the CALLING method's body mentions a
        // "core" + PascalCase method name -- a real false-positive risk
        // for any project that happens to use that same naming
        // convention on its own, independently-written helpers. An
        // explicit marker comment placed directly on the generated
        // helper's OWN declaration is unambiguous regardless of naming
        // style: the server now checks whether the CALLED method's
        // definition carries this exact marker, not whether the
        // caller's method NAME merely looks like it might be one.
        String helperText = "// @CloneGuardGenerated\n"
                + "private " + helperReturnTypeText + " " + finalHelperName + "(" + paramListText + ") {\n"
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
            // FIX (found live, VowelCountDemo test -- Type 1 clone with a
            // variable declared inside a for-loop's BODY, e.g.
            // `char ch = lower.charAt(i);` nested one level inside the
            // loop, itself inside the matched shared block): the previous
            // version below only checked ONE level deep -- a directly
            // top-level declaration statement, or a for-loop's own
            // `init` clause -- and never walked INTO a loop's or
            // conditional's body to find variables declared there. Such a
            // variable was invisible to this scan, then correctly found by
            // the separate reference-search further down (which DOES walk
            // the full nested tree via PsiTreeUtil.findChildrenOfType),
            // and consequently misidentified as an external dependency
            // with nowhere to trace it back to -- producing "CloneGuard
            // cannot safely extract" on a pair that was actually a
            // byte-for-byte identical Type 1 clone with nothing genuinely
            // external at all. Recursively collecting every local
            // variable and foreach iteration parameter declared anywhere
            // in the block's subtree closes this gap in one pass, and
            // subsumes all three of the previous manual cases: a
            // top-level declaration is still a PsiLocalVariable, and a
            // for-loop's init variable is still a PsiLocalVariable too --
            // both are found by the same recursive search below, along
            // with anything nested arbitrarily deep inside loop bodies,
            // if-blocks, or blocks within blocks.
            locallyAvailable.addAll(PsiTreeUtil.findChildrenOfType(stmt, PsiLocalVariable.class));
            // FIX (found live, EvenCountDemo test -- immediately after the
            // previous fix): findChildrenOfType only searches DESCENDANTS
            // of the element passed in, never the element itself. When the
            // matched shared block's foreach loop IS one of the top-level
            // statements being scanned (stmt == the PsiForeachStatement
            // itself, not a container holding one), the recursive search
            // below alone finds nothing -- it's looking for a loop nested
            // INSIDE this loop, not this loop itself. Restoring an
            // explicit self-check alongside the recursive one (for a
            // foreach loop genuinely nested inside another matched
            // statement, e.g. a loop within a loop) covers both cases.
            if (stmt instanceof PsiForeachStatement selfFe) {
                locallyAvailable.add(selfFe.getIterationParameter());
            }
            for (PsiForeachStatement fe : PsiTreeUtil.findChildrenOfType(stmt, PsiForeachStatement.class)) {
                locallyAvailable.add(fe.getIterationParameter());
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

        PsiFile psiFile = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(targetFile));
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

        int oldDuplicateLines = countLines(plan.duplicateMethod.getBody() != null ? plan.duplicateMethod.getBody().getText() : "");
        int newDuplicateLines = countLines(plan.newDuplicateBodyText);
        int duplicatedLinesEliminated = Math.max(0, oldDuplicateLines - newDuplicateLines);

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
        com.cloneguard.services.MetricsTrackerService.getInstance(project).recordRefactor("delegate", duplicatedLinesEliminated, cloneTypeLabel);

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
        PsiMethod[] resolvedPair = resolveDistinctPairByName(psiFile, canonical, duplicate);
        PsiMethod canonicalMethod = resolvedPair != null ? resolvedPair[0] : null;
        PsiMethod duplicateMethod = resolvedPair != null ? resolvedPair[1] : null;
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
        PsiType canonicalReturn = canonicalMethod.getReturnType();
        PsiType duplicateReturn = duplicateMethod.getReturnType();

        boolean directlyCompatible = signaturesCompatible(canonicalParams, canonicalReturn, duplicateParams, duplicateReturn);

        // RESTORED (this fix was present earlier this session but got
        // dropped when this file was later rebuilt from an older uploaded
        // copy that predated it -- confirmed live: isPrimeIterative()/
        // isPrimeHelper() started refusing again with the old, pre-wrapper-
        // search error message). A semantically-correct match can still
        // have an incompatible signature, because Layer 2 matches on
        // BEHAVIOR, not public callability. Search the same class for a
        // sibling method that already calls one of the two matched methods
        // and has a signature compatible with the OTHER one -- an existing
        // public wrapper around the real logic (e.g. isPrimeRecursive(int n)
        // { return isPrimeHelper(n, 2); }). Try both directions: first
        // "does something wrap canonical with duplicate's signature", and
        // if that fails, "does something wrap duplicate with canonical's
        // signature" instead -- whichever of the two methods already has
        // an existing wrapper is left untouched; the other is redirected
        // through it.
        PsiMethod methodToRewrite = duplicateMethod;
        PsiMethod methodLeftAlone = canonicalMethod;
        PsiMethod delegationTarget = canonicalMethod;
        boolean viaSibling = false;
        if (!directlyCompatible) {
            PsiMethod sibling = findCompatibleWrapper(canonicalClass, canonicalMethod, duplicateMethod, duplicateParams, duplicateReturn);
            if (sibling != null) {
                delegationTarget = sibling;
                viaSibling = true;
            } else {
                PsiMethod reverseSibling = findCompatibleWrapper(canonicalClass, duplicateMethod, canonicalMethod, canonicalParams, canonicalReturn);
                if (reverseSibling != null) {
                    methodToRewrite = canonicalMethod;
                    methodLeftAlone = duplicateMethod;
                    delegationTarget = reverseSibling;
                    viaSibling = true;
                } else {
                    if (canonicalParams.length != duplicateParams.length) {
                        return DelegationPlan.abort("CloneGuard — Cannot Delegate Safely",
                                canonical + "() and " + duplicate + "() take a different number of parameters — " +
                                "delegation needs a matching signature, and no existing method in this class " +
                                "already wraps either one with a compatible signature. No changes were made.",
                                JOptionPane.WARNING_MESSAGE);
                    }
                    for (int i = 0; i < canonicalParams.length; i++) {
                        if (!canonicalParams[i].getType().equals(duplicateParams[i].getType())) {
                            return DelegationPlan.abort("CloneGuard — Cannot Delegate Safely",
                                    "Parameter " + (i + 1) + " type differs between " + canonical + "() and " + duplicate +
                                    "() — delegation needs a matching signature, and no existing method in this class " +
                                    "already wraps either one with a compatible signature. No changes were made.",
                                    JOptionPane.WARNING_MESSAGE);
                        }
                    }
                    return DelegationPlan.abort("CloneGuard — Cannot Delegate Safely",
                            "Return types differ between " + canonical + "() and " + duplicate + "() — " +
                            "delegation needs a matching signature, and no existing method in this class " +
                            "already wraps either one with a compatible signature. No changes were made.",
                            JOptionPane.WARNING_MESSAGE);
                }
            }
        }

        String rewriteName = methodToRewrite.getName();
        String aloneName = methodLeftAlone.getName();
        PsiParameter[] rewriteParams = methodToRewrite.getParameterList().getParameters();
        String targetName = delegationTarget.getName();
        PsiType targetReturn = delegationTarget.getReturnType();
        String callArgsText = Arrays.stream(rewriteParams)
                .map(PsiParameter::getName).reduce((a, b) -> a + ", " + b).orElse("");
        boolean isVoid = targetReturn != null && "void".equals(targetReturn.getPresentableText());
        String callLine = isVoid
                ? targetName + "(" + callArgsText + ");"
                : "return " + targetName + "(" + callArgsText + ");";

        String confirmMessage = viaSibling
                ? "CloneGuard found a " + cloneTypeLabel + ":\n\n" +
                  "  " + canonical + "()  ↔  " + duplicate + "()\n\n" +
                  aloneName + "() has the shared logic but its signature doesn't match " + rewriteName + "(). " +
                  "However, " + targetName + "() in this class already calls " + aloneName + "() with a " +
                  "signature that matches " + rewriteName + "() exactly.\n\n" +
                  "Proposed refactoring (Method Delegation, via existing wrapper):\n" +
                  "  • " + rewriteName + "() will call " + targetName + "() directly\n" +
                  "  • " + aloneName + "() and " + targetName + "() are left completely unchanged\n\n" +
                  "Apply this refactoring now?"
                : "CloneGuard found a " + cloneTypeLabel + ":\n\n" +
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
        plan.duplicateMethod = methodToRewrite;
        plan.confirmMessage = confirmMessage;
        plan.newDuplicateBodyText = "{\n" + callLine + "\n}";
        return plan;
    }

    /**
     * True if two signatures (params positionally + return type) match
     * exactly. Extracted so both the direct-compatibility check and the
     * sibling-wrapper search share one definition of "compatible".
     */
    private boolean signaturesCompatible(PsiParameter[] paramsA, PsiType returnA, PsiParameter[] paramsB, PsiType returnB) {
        if (paramsA.length != paramsB.length) return false;
        for (int i = 0; i < paramsA.length; i++) {
            if (!paramsA[i].getType().equals(paramsB[i].getType())) return false;
        }
        return returnA != null && returnB != null && returnA.equals(returnB);
    }

    /**
     * Searches the containing class for a method that (a) is not the
     * canonical or duplicate itself, (b) has a signature compatible with
     * duplicateParams/duplicateReturn, and (c) actually calls
     * canonicalMethod somewhere in its body. This is the "existing public
     * wrapper" pattern -- e.g. isPrimeRecursive(int n) wrapping
     * isPrimeHelper(int n, int divisor) by seeding divisor=2. Returns null
     * if no such method exists.
     */
    private PsiMethod findCompatibleWrapper(PsiClass containingClass, PsiMethod canonicalMethod, PsiMethod duplicateMethod,
                                             PsiParameter[] duplicateParams, PsiType duplicateReturn) {
        for (PsiMethod candidate : containingClass.getMethods()) {
            if (candidate.equals(canonicalMethod) || candidate.equals(duplicateMethod)) continue;
            PsiParameter[] candidateParams = candidate.getParameterList().getParameters();
            PsiType candidateReturn = candidate.getReturnType();
            if (!signaturesCompatible(candidateParams, candidateReturn, duplicateParams, duplicateReturn)) continue;

            PsiCodeBlock body = candidate.getBody();
            if (body == null) continue;
            boolean callsCanonical = PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression.class).stream()
                    .anyMatch(call -> {
                        PsiMethod resolved = call.resolveMethod();
                        return resolved != null && resolved.equals(canonicalMethod);
                    });
            if (callsCanonical) return candidate;
        }
        return null;
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

    /**
     * Like statementsStructurallyEqual, but compares an ENTIRE method body
     * (every statement must match, not just a contiguous window) and
     * RETURNS the resulting identifier mapping instead of a boolean.
     * Pull Up Method's Type 2 support (see buildPullUpPlan) needs to know
     * WHICH identifiers differ, not just whether the bodies are
     * consistent, so it can check each one against PSI to rule out
     * renamed FIELDS specifically -- see the design note in
     * buildPullUpPlan for why that distinction is the one that actually
     * matters. Returns null if the bodies have a different statement
     * count or don't tokenize into a consistent structural match at all.
     */
    private Map<String, String> buildFullBodyIdentifierMapping(PsiStatement[] a, PsiStatement[] b) {
        if (a.length != b.length) return null;
        Map<String, String> aToB = new LinkedHashMap<>();
        Map<String, String> bToA = new LinkedHashMap<>();
        for (int k = 0; k < a.length; k++) {
            List<String[]> tokensA = tokenizeStatement(a[k].getText());
            List<String[]> tokensB = tokenizeStatement(b[k].getText());
            if (tokensA.size() != tokensB.size()) return null;
            for (int t = 0; t < tokensA.size(); t++) {
                String kindA = tokensA.get(t)[0], valA = tokensA.get(t)[1];
                String kindB = tokensB.get(t)[0], valB = tokensB.get(t)[1];
                if (!kindA.equals(kindB)) return null;
                if (kindA.equals("id")) {
                    String mapped = aToB.get(valA);
                    if (mapped != null) {
                        if (!mapped.equals(valB)) return null;
                    } else if (bToA.containsKey(valB)) {
                        return null;
                    } else {
                        aToB.put(valA, valB);
                        bToA.put(valB, valA);
                    }
                } else if (!valA.equals(valB)) {
                    return null;
                }
            }
        }
        return aToB;
    }

    /**
     * True if `identifierName`, as it appears inside `body`, ever
     * resolves (via real PSI reference resolution, not text matching) to
     * a field. Used to distinguish a renamed LOCAL variable/parameter
     * (safe -- invisible outside the method, so Pull Up can keep either
     * name with zero external impact) from a renamed FIELD (unsafe --
     * two different pieces of subclass state, not just two names for the
     * same thing).
     */
    private boolean identifierResolvesToField(PsiCodeBlock body, String identifierName) {
        for (PsiReferenceExpression ref : PsiTreeUtil.findChildrenOfType(body, PsiReferenceExpression.class)) {
            if (!identifierName.equals(ref.getReferenceName())) continue;
            if (ref.resolve() instanceof PsiField) return true;
        }
        return false;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Same simple, transparent line-counting approach as
     * MetricsTrackerService.countLines() — raw physical line count, no
     * blank/comment filtering. Used here specifically to compute
     * "duplicated lines eliminated" at each refactor's success point,
     * from text already sitting in the plan (before/after body text),
     * before it's handed off to the metrics service to record.
     */
    private static int countLines(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) text.lines().count();
    }


    // ─────────────────────────────────────────────────────────────────────
    // PULL UP METHOD & PUSH DOWN METHOD
    // ─────────────────────────────────────────────────────────────────────
    //
    // Pull Up reuses the (canonical, duplicate) convention, same as extract()
    // and delegate() — it's still fundamentally "two clone methods", just
    // living in sibling subclasses of a common superclass instead of the
    // same class.
    //
    // SCOPE DECISION (documented the same way 2.1/3.3 are documented as
    // partial scope elsewhere in this codebase, updated after adding Type
    // 2 support): Pull Up now accepts both an exact match after
    // whitespace normalization (Type 1) AND a renamed-identifier match
    // (Type 2) -- but only when EVERY identifier that differs between the
    // two bodies is a LOCAL VARIABLE or PARAMETER, never a FIELD. That
    // distinction, not "renamed vs. not renamed," is what actually
    // determines safety: a local variable is invisible outside the
    // method it's declared in, so canonicalMethod's own text (with its
    // own local names) is already fully self-consistent and safe to move
    // as-is -- no call-site rewriting needed anywhere, because nothing
    // outside the method ever referenced that local name to begin with.
    // A renamed FIELD is a genuinely different situation: it means the
    // two subclasses are backed by different pieces of state (e.g.
    // Dog.breed vs Cat.species), and silently picking one over the other
    // would be a real behavior change, not a cosmetic rename -- that case
    // is still refused. See buildFullBodyIdentifierMapping() and
    // identifierResolvesToField() for the implementation, and
    // buildPullUpPlan()'s safety measure #4 for where this is applied.
    //
    // Type 3 (near-miss -- bodies that genuinely differ, e.g. an added
    // guard clause) is still out of scope: there's no single correct body
    // to move in that case without deciding what happens to the
    // difference, which is a design choice, not a mechanical rename.
    //
    // Push Down does NOT fit the (canonical, duplicate) convention — only
    // one method is involved (sitting in the superclass), not two. Its
    // signature is pushDown(methodName, targetSubclassName, ...) instead.

    // ── Routing helper, called from CloneGuardToolWindowFactory BEFORE
    // falling back to extract(). Does the same "same class?" / "shared
    // non-Object superclass?" checks buildPullUpPlan() does internally, but
    // as a cheap standalone read so the caller can decide which button-
    // click path to take WITHOUT showing a dialog first. Returns true and
    // fully handles the refactor (including its own confirm dialog and
    // write action) if this pair is a genuine Pull Up case; returns false
    // and does nothing at all — no dialog, no side effects — if it isn't,
    // so the caller can silently fall through to extract() instead.
    public boolean tryPullUpIfApplicable(String canonical, String duplicate, String cloneTypeLabel, java.util.function.Consumer<PsiFile> onComplete) {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) return false;
        VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (vf == null) return false;
        return tryPullUpIfApplicable(vf, canonical, duplicate, cloneTypeLabel, onComplete);
    }

    /**
     * Same routing check as the no-file overload above, but against an
     * EXPLICIT target file rather than whatever editor currently has
     * focus. Added specifically so Scenario 1 (InlineSuggestionListener)
     * can route its paste-detection notification through Pull Up
     * correctly — the pasted-into file (captured as `vf` at paste time)
     * isn't guaranteed to still be the focused editor by the time the
     * user actually clicks the notification's action button, since
     * notifications are asynchronous and focus can shift in between.
     * Scenario 1's extract()/delegate() calls already take this same
     * explicit-VirtualFile approach for exactly that reason; this
     * overload brings Pull Up's routing check in line with that existing
     * convention instead of silently trusting "whatever's focused now".
     */
    public boolean tryPullUpIfApplicable(VirtualFile targetFile, String canonical, String duplicate, String cloneTypeLabel, java.util.function.Consumer<PsiFile> onComplete) {
        if (targetFile == null || !targetFile.isValid()) return false;

        PsiDocumentManager.getInstance(project).commitAllDocuments();
        PsiFile psiFile = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(targetFile));
        if (psiFile == null) return false;

        if (!isPullUpApplicable(psiFile, canonical, duplicate)) return false;

        pullUp(targetFile, canonical, duplicate, cloneTypeLabel, onComplete);
        return true;
    }

    /**
     * Read-only check: would tryPullUpIfApplicable() actually route this
     * pair to Pull Up Method, without performing any refactor or touching
     * the file. Exists specifically so UI code building a button label
     * ahead of time (e.g. CloneGuardToolWindowFactory, deciding whether to
     * show "Extract \u2192" or "Pull Up \u2192" BEFORE the user clicks) can ask
     * this exact question cheaply and correctly, instead of the button
     * label being a guess that the click can silently contradict. Kept in
     * lock-step with tryPullUpIfApplicable() by having that method call
     * this one rather than each maintaining its own copy of the check.
     */
    public boolean isPullUpApplicable(PsiFile psiFile, String canonical, String duplicate) {
        if (psiFile == null) return false;
        final String canonicalName = canonical;
        final String duplicateName = duplicate;
        Boolean applicable = ReadAction.compute(() -> {
            PsiMethod[] resolvedPair = resolveDistinctPairByName(psiFile, canonicalName, duplicateName);
            PsiMethod a = resolvedPair != null ? resolvedPair[0] : null;
            PsiMethod b = resolvedPair != null ? resolvedPair[1] : null;
            if (a == null || b == null) return false;
            PsiClass classA = a.getContainingClass();
            PsiClass classB = b.getContainingClass();
            if (classA == null || classB == null || classA.equals(classB)) return false;
            PsiClass superA = classA.getSuperClass();
            PsiClass superB = classB.getSuperClass();
            if (superA == null || superB == null || !superA.equals(superB)) return false;
            return !"java.lang.Object".equals(superA.getQualifiedName());
        });
        return Boolean.TRUE.equals(applicable);
    }

    /**
     * Convenience overload for UI code that only has a VirtualFile handy
     * (e.g. computing a button label before the file is necessarily open
     * in an editor), matching the same VirtualFile-vs-editor convention
     * already used by the extract()/delegate()/pullUp() family above.
     */
    public boolean isPullUpApplicable(VirtualFile targetFile, String canonical, String duplicate) {
        if (targetFile == null || !targetFile.isValid()) return false;
        // FIX (found live, Pull Up demo test): PsiManager.findFile() is a
        // PSI read and was being called here with no ReadAction wrapper.
        // This runs synchronously from CloneGuardToolWindowFactory while
        // building each clone-group card's button label on the EDT,
        // which is exactly why "Read access is allowed from inside
        // read-action only" warnings appeared repeatedly right after
        // every scan -- confirmed by matching the warning's timestamps
        // against the scan-complete log line. Wrapping the read in
        // ReadAction.compute() here matches the pattern already used
        // everywhere else in this file (see tryPullUpIfApplicable above).
        PsiFile psiFile = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(targetFile));
        return isPullUpApplicable(psiFile, canonical, duplicate);
    }

    public void pullUp(String canonical, String duplicate, String cloneTypeLabel, java.util.function.Consumer<PsiFile> onComplete) {
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
        pullUp(vf, canonical, duplicate, cloneTypeLabel, onComplete);
    }

    public void pullUp(VirtualFile targetFile, String canonical, String duplicate, String cloneTypeLabel, java.util.function.Consumer<PsiFile> onComplete) {
        String filePath = (targetFile != null) ? targetFile.getPath() : "";
        String pairKey = filePath + "::pullup::" + (canonical.compareTo(duplicate) < 0
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

        PsiFile psiFile = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(targetFile));
        if (psiFile == null) {
            showDialog("Could not read the target file. Make sure it is saved.", "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }

        PullUpPlan plan = ReadAction.compute(() -> buildPullUpPlan(psiFile, canonical, duplicate, cloneTypeLabel));

        if (plan.aborted) {
            showDialog(plan.abortMessage, plan.abortTitle, plan.abortMessageType);
            return;
        }

        int choice = Messages.showYesNoDialog(plan.confirmMessage, "CloneGuard — Confirm Refactor", Messages.getQuestionIcon());
        if (choice != Messages.YES) return;

        // Of the two duplicate copies, one (methodInClassA's content)
        // effectively just relocates into the superclass — it isn't
        // "eliminated," it moved. It's specifically methodInClassB's
        // copy that's the genuinely eliminated duplicate, so that's what
        // counts here, captured before the delete happens below.
        int duplicatedLinesEliminated = countLines(plan.methodInClassB.getText());

        final boolean[] writeFailed = {false};
        final String[] writeFailureMessage = {null};

        WriteCommandAction.runWriteCommandAction(project, "CloneGuard Pull Up Method", null, () -> {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
            PsiMethod newSuperclassMethod;
            try {
                newSuperclassMethod = factory.createMethodFromText(plan.methodTextForSuperclass, plan.superClass);
            } catch (Exception ex) {
                writeFailed[0] = true;
                writeFailureMessage[0] = ex.getMessage();
                return;
            }

            // Add to the superclass first, THEN delete both subclass copies —
            // if createMethodFromText or the add() call throws, we bail out
            // above before anything is deleted, so a failed Pull Up never
            // leaves the codebase with the method missing from all three
            // places at once.
            plan.superClass.add(newSuperclassMethod);
            plan.methodInClassA.delete();
            plan.methodInClassB.delete();

            com.intellij.psi.codeStyle.CodeStyleManager csm = com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project);
            csm.reformat(plan.superClass);
        });

        if (writeFailed[0]) {
            showDialog(
                    "CloneGuard's Pull Up Method refactoring failed a safety check and was NOT applied:\n\n" +
                    writeFailureMessage[0] + "\n\nYour file was not modified.",
                    "CloneGuard — Refactor Aborted", JOptionPane.ERROR_MESSAGE);
            return;
        }

        extractedPairs.put(pairKey, System.currentTimeMillis());
        com.cloneguard.services.MetricsTrackerService.getInstance(project).recordRefactor("pullUp", duplicatedLinesEliminated, cloneTypeLabel);

        showDialog(
                "✅ Pull Up Method applied!\n\n" +
                canonical + "() moved into " + plan.superClass.getName() + ".\n" +
                "Both " + canonical + "() and " + duplicate + "() are now inherited from there.\n\n" +
                "Re-scanning the file now to refresh results...",
                "CloneGuard — Refactor Complete", JOptionPane.INFORMATION_MESSAGE);

        onComplete.accept(psiFile);
    }

    private static class PullUpPlan {
        boolean aborted;
        String abortTitle;
        String abortMessage;
        int abortMessageType;

        PsiClass superClass;
        PsiMethod methodInClassA;
        PsiMethod methodInClassB;
        String methodTextForSuperclass;
        String confirmMessage;

        static PullUpPlan abort(String title, String message, int type) {
            PullUpPlan p = new PullUpPlan();
            p.aborted = true;
            p.abortTitle = title;
            p.abortMessage = message;
            p.abortMessageType = type;
            return p;
        }
    }

    private PullUpPlan buildPullUpPlan(PsiFile psiFile, String canonical, String duplicate, String cloneTypeLabel) {
        PsiMethod[] resolvedPair = resolveDistinctPairByName(psiFile, canonical, duplicate);
        PsiMethod canonicalMethod = resolvedPair != null ? resolvedPair[0] : null;
        PsiMethod duplicateMethod = resolvedPair != null ? resolvedPair[1] : null;
        if (canonicalMethod == null || duplicateMethod == null) {
            return PullUpPlan.abort("CloneGuard",
                    "Could not find one or both methods (" + canonical + "(), " + duplicate + "()) in this file. " +
                    "The file may have changed since this result was shown — try re-scanning.",
                    JOptionPane.WARNING_MESSAGE);
        }

        PsiClass classA = canonicalMethod.getContainingClass();
        PsiClass classB = duplicateMethod.getContainingClass();
        if (classA == null || classB == null) {
            return PullUpPlan.abort("CloneGuard",
                    "Could not resolve the containing class for one or both methods. No changes were made.",
                    JOptionPane.WARNING_MESSAGE);
        }

        // Safety measure #1: Pull Up only makes sense across two DIFFERENT
        // sibling subclasses. If they're the same class, this isn't a Pull
        // Up case at all — the caller should use Extract Method instead.
        if (classA.equals(classB)) {
            return PullUpPlan.abort("CloneGuard — Not a Pull Up Case",
                    canonical + "() and " + duplicate + "() are both in the same class (" + classA.getName() +
                    "). Pull Up Method only applies when the two methods live in DIFFERENT sibling subclasses " +
                    "that share a common superclass. Try Extract Method instead. No changes were made.",
                    JOptionPane.WARNING_MESSAGE);
        }

        // Safety measure #2: the two classes must share a real, user-defined
        // common superclass — not just implicitly both extending
        // java.lang.Object. Pulling a method up into Object is neither
        // meaningful nor possible here.
        PsiClass superClassA = classA.getSuperClass();
        PsiClass superClassB = classB.getSuperClass();
        if (superClassA == null || superClassB == null || !superClassA.equals(superClassB)
                || "java.lang.Object".equals(superClassA.getQualifiedName())) {
            return PullUpPlan.abort("CloneGuard — Not a Pull Up Case",
                    classA.getName() + " and " + classB.getName() + " do not share a common user-defined superclass. " +
                    "Pull Up Method requires both classes to directly extend the same parent class. No changes were made.",
                    JOptionPane.WARNING_MESSAGE);
        }
        PsiClass superClass = superClassA;

        PsiCodeBlock bodyA = canonicalMethod.getBody();
        PsiCodeBlock bodyB = duplicateMethod.getBody();
        if (bodyA == null || bodyB == null) {
            return PullUpPlan.abort("CloneGuard",
                    "One of the methods has no body (e.g. abstract or interface method) — cannot pull up.",
                    JOptionPane.WARNING_MESSAGE);
        }

        // Safety measure #3: signature compatibility (same param types
        // positionally, same return type) — the superclass will only have
        // ONE version of this method, so both subclasses' call sites must
        // already agree on the contract.
        PsiParameter[] paramsA = canonicalMethod.getParameterList().getParameters();
        PsiParameter[] paramsB = duplicateMethod.getParameterList().getParameters();
        PsiType returnA = canonicalMethod.getReturnType();
        PsiType returnB = duplicateMethod.getReturnType();
        if (!signaturesCompatible(paramsA, returnA, paramsB, returnB)) {
            return PullUpPlan.abort("CloneGuard — Cannot Pull Up Safely",
                    canonical + "() and " + duplicate + "() do not have compatible signatures (parameter types or " +
                    "return type differ). Pull Up Method needs one identical signature both subclasses can inherit. " +
                    "No changes were made.",
                    JOptionPane.WARNING_MESSAGE);
        }

        // Safety measure #4 (SCOPE now covers Type 1 AND Type 2):
        //
        // Type 1 (exact match) is handled first, same as before -- if the
        // two bodies are byte-for-byte identical after whitespace
        // normalization, there's nothing further to check here.
        //
        // Type 2 (renamed identifiers) is now ALSO accepted, but only
        // under one condition: every identifier that differs between the
        // two bodies must be a LOCAL VARIABLE or PARAMETER, never a
        // FIELD. This distinction is what actually matters, not renaming
        // in general -- a local variable is invisible outside the method
        // it's declared in, so canonicalMethod's own text (with its own
        // local names) is already fully self-consistent and 100% safe to
        // move as-is, regardless of what classB's copy happened to call
        // the same local. A renamed FIELD is a different situation
        // entirely: it means the two subclasses are backed by genuinely
        // different pieces of state (e.g. Dog.breed vs Cat.species), and
        // silently picking one over the other when moving the method up
        // would be a real behavior change, not just a rename. That case
        // is refused, same as before, rather than guessed at.
        String normalizedA = bodyA.getText().replaceAll("\\s+", " ").trim();
        String normalizedB = bodyB.getText().replaceAll("\\s+", " ").trim();
        boolean isExactMatch = normalizedA.equals(normalizedB);
        String detectedCloneKind = "Type 1 (exact match)";

        if (!isExactMatch) {
            Map<String, String> renameMapping = buildFullBodyIdentifierMapping(
                    bodyA.getStatements(), bodyB.getStatements());
            if (renameMapping == null) {
                return PullUpPlan.abort("CloneGuard — Cannot Pull Up Safely",
                        canonical + "() and " + duplicate + "() are not structurally equivalent, even allowing for " +
                        "renamed identifiers (different statement count or shape). Pull Up Method supports exact " +
                        "Type 1 clones and Type 2 clones where every difference is a renamed LOCAL variable or " +
                        "parameter. No changes were made.",
                        JOptionPane.WARNING_MESSAGE);
            }
            for (Map.Entry<String, String> entry : renameMapping.entrySet()) {
                String nameInA = entry.getKey();
                String nameInB = entry.getValue();
                if (nameInA.equals(nameInB)) continue; // same name -- not actually renamed
                if (identifierResolvesToField(bodyA, nameInA) || identifierResolvesToField(bodyB, nameInB)) {
                    return PullUpPlan.abort("CloneGuard — Cannot Pull Up Safely",
                            canonical + "() and " + duplicate + "() are structurally identical except for renamed " +
                            "identifiers, but \"" + nameInA + "\" / \"" + nameInB + "\" resolves to a FIELD, not a " +
                            "local variable. Pulling up would silently pick one subclass's field over the other's " +
                            "-- CloneGuard only auto-applies Type 2 Pull Up when every difference is a local " +
                            "variable or parameter name. No changes were made.",
                            JOptionPane.WARNING_MESSAGE);
                }
            }
            detectedCloneKind = "Type 2 (renamed local variables)";
        }

        // Safety measure #5: the method must not depend on anything that
        // only exists on classA specifically (a field or another method
        // declared directly on classA, not inherited from the superclass
        // or above). If it does, moving it up would break compilation for
        // classB, which never had that member in the first place. Checked
        // from BOTH sides — the two bodies are textually identical, but
        // identifiers inside them resolve independently in each class's
        // scope.
        String dependencyErrorA = findSubclassOnlyDependency(canonicalMethod, bodyA, classA);
        if (dependencyErrorA != null) {
            return PullUpPlan.abort("CloneGuard — Cannot Pull Up Safely",
                    canonical + "() references " + dependencyErrorA + ", which only exists on " + classA.getName() +
                    " — moving this method to " + superClass.getName() + " would break " + classB.getName() +
                    ", which has no such member. No changes were made.",
                    JOptionPane.WARNING_MESSAGE);
        }
        String dependencyErrorB = findSubclassOnlyDependency(duplicateMethod, bodyB, classB);
        if (dependencyErrorB != null) {
            return PullUpPlan.abort("CloneGuard — Cannot Pull Up Safely",
                    duplicate + "() references " + dependencyErrorB + ", which only exists on " + classB.getName() +
                    " — moving this method to " + superClass.getName() + " would break " + classA.getName() +
                    ", which has no such member. No changes were made.",
                    JOptionPane.WARNING_MESSAGE);
        }

        // Safety measure #6: the superclass must not already declare a
        // method with this same name and signature — that would be an
        // unintended override collision, not a clean Pull Up.
        for (PsiMethod existing : superClass.getMethods()) {
            if (existing.getName().equals(canonicalMethod.getName())
                    && signaturesCompatible(existing.getParameterList().getParameters(), existing.getReturnType(), paramsA, returnA)) {
                return PullUpPlan.abort("CloneGuard — Cannot Pull Up Safely",
                        superClass.getName() + " already declares a method named " + canonical + "() with a matching " +
                        "signature. Pulling up would silently collide with it. No changes were made.",
                        JOptionPane.WARNING_MESSAGE);
            }
        }

        String confirmMessage =
                "CloneGuard found a " + cloneTypeLabel + " across sibling subclasses:\n\n" +
                // FIX (found live, testing): canonical/duplicate can
                // already be class-qualified ("Dog.describe") whenever
                // extractFunctions() had to disambiguate a name collision
                // -- concatenating classA.getName() + "." + canonical in
                // that case doubled the prefix ("Dog.Dog.describe()").
                // Using the method's own bare getName() here instead of
                // the raw parameter guarantees exactly one prefix
                // regardless of which format the caller passed in.
                "  " + classA.getName() + "." + canonicalMethod.getName() + "()  ↔  " + classB.getName() + "." + duplicateMethod.getName() + "()\n\n" +
                "Detected as: " + detectedCloneKind + ". Both extend " + superClass.getName() + ".\n\n" +
                "Proposed refactoring (Pull Up Method):\n" +
                "  • " + canonicalMethod.getName() + "() moves into " + superClass.getName() +
                (isExactMatch ? "" : ", using " + classA.getName() + "'s local variable names") + "\n" +
                "  • The copies in " + classA.getName() + " and " + classB.getName() + " are removed\n" +
                "  • Both subclasses now inherit the single shared implementation\n\n" +
                "Apply this refactoring now?";

        PullUpPlan plan = new PullUpPlan();
        plan.aborted = false;
        plan.superClass = superClass;
        plan.methodInClassA = canonicalMethod;
        plan.methodInClassB = duplicateMethod;
        plan.methodTextForSuperclass = canonicalMethod.getText();
        plan.confirmMessage = confirmMessage;
        return plan;
    }

    /**
     * Scans a method body for references to fields or methods that are
     * declared directly on `owningClass` itself (not inherited from
     * anything above it) — i.e. members the OTHER sibling subclass
     * wouldn't have. Returns a human-readable description of the first
     * such dependency found, or null if the method is safe to move up.
     */
    private String findSubclassOnlyDependency(PsiMethod method, PsiCodeBlock body, PsiClass owningClass) {
        for (PsiReferenceExpression ref : PsiTreeUtil.findChildrenOfType(body, PsiReferenceExpression.class)) {
            PsiElement resolved = ref.resolve();
            if (resolved == null) continue;

            if (resolved instanceof PsiField) {
                PsiField field = (PsiField) resolved;
                PsiClass fieldOwner = field.getContainingClass();
                if (fieldOwner != null && fieldOwner.equals(owningClass)) {
                    return "field \"" + field.getName() + "\"";
                }
            } else if (resolved instanceof PsiMethod) {
                PsiMethod calledMethod = (PsiMethod) resolved;
                if (calledMethod.equals(method)) continue; // recursive self-call is fine
                PsiClass methodOwner = calledMethod.getContainingClass();
                if (methodOwner != null && methodOwner.equals(owningClass)) {
                    return "method \"" + calledMethod.getName() + "()\"";
                }
            }
        }
        return null;
    }


    // ── Push Down Method ────────────────────────────────────────────────
    // NOTE on calling convention: unlike pullUp()/extract()/delegate(),
    // this is NOT a (canonical, duplicate) pair — there's only one method
    // involved, currently sitting in a superclass, being moved down into
    // ONE named subclass.

    public void pushDown(String methodName, String targetSubclassName, java.util.function.Consumer<PsiFile> onComplete) {
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
        pushDown(vf, methodName, targetSubclassName, onComplete);
    }

    public void pushDown(VirtualFile targetFile, String methodName, String targetSubclassName, java.util.function.Consumer<PsiFile> onComplete) {
        String filePath = (targetFile != null) ? targetFile.getPath() : "";
        String pairKey = filePath + "::pushdown::" + methodName + "||" + targetSubclassName;
        Long lastAt = extractedPairs.get(pairKey);
        if (lastAt != null && (System.currentTimeMillis() - lastAt) < EXTRACTED_PAIR_GUARD_MS) {
            return;
        }

        if (targetFile == null || !targetFile.isValid()) {
            showDialog("The target file is no longer available.", "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }

        PsiDocumentManager.getInstance(project).commitAllDocuments();

        PsiFile psiFile = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(targetFile));
        if (psiFile == null) {
            showDialog("Could not read the target file. Make sure it is saved.", "CloneGuard", JOptionPane.WARNING_MESSAGE);
            return;
        }

        PushDownPlan plan = ReadAction.compute(() -> buildPushDownPlan(psiFile, methodName, targetSubclassName));

        if (plan.aborted) {
            showDialog(plan.abortMessage, plan.abortTitle, plan.abortMessageType);
            return;
        }

        int choice = Messages.showYesNoDialog(plan.confirmMessage, "CloneGuard — Confirm Refactor", Messages.getQuestionIcon());
        if (choice != Messages.YES) return;

        final boolean[] writeFailed = {false};
        final String[] writeFailureMessage = {null};

        WriteCommandAction.runWriteCommandAction(project, "CloneGuard Push Down Method", null, () -> {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
            PsiMethod newSubclassMethod;
            try {
                newSubclassMethod = factory.createMethodFromText(plan.methodTextForSubclass, plan.targetSubclass);
            } catch (Exception ex) {
                writeFailed[0] = true;
                writeFailureMessage[0] = ex.getMessage();
                return;
            }

            // Same ordering principle as Pull Up: add the new copy FIRST,
            // only delete the superclass original if that succeeds, so a
            // failed Push Down never leaves the method missing everywhere.
            plan.targetSubclass.add(newSubclassMethod);
            plan.methodInSuperclass.delete();

            com.intellij.psi.codeStyle.CodeStyleManager csm = com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project);
            csm.reformat(plan.targetSubclass);
        });

        if (writeFailed[0]) {
            showDialog(
                    "CloneGuard's Push Down Method refactoring failed a safety check and was NOT applied:\n\n" +
                    writeFailureMessage[0] + "\n\nYour file was not modified.",
                    "CloneGuard — Refactor Aborted", JOptionPane.ERROR_MESSAGE);
            return;
        }

        extractedPairs.put(pairKey, System.currentTimeMillis());
        // Push Down isn't a duplication fix — there was only ever one
        // copy of the method — so 0 duplicated lines eliminated is the
        // honest number here. It still counts toward the refactor-type
        // breakdown on the dashboard.
        com.cloneguard.services.MetricsTrackerService.getInstance(project).recordRefactor("pushDown", 0);

        showDialog(
                "✅ Push Down Method applied!\n\n" +
                methodName + "() moved out of " + plan.superClass.getName() + " into " + targetSubclassName + ".\n" +
                "Other subclasses of " + plan.superClass.getName() + " no longer inherit it.\n\n" +
                "Re-scanning the file now to refresh results...",
                "CloneGuard — Refactor Complete", JOptionPane.INFORMATION_MESSAGE);

        onComplete.accept(psiFile);
    }

    private static class PushDownPlan {
        boolean aborted;
        String abortTitle;
        String abortMessage;
        int abortMessageType;

        PsiClass superClass;
        PsiClass targetSubclass;
        PsiMethod methodInSuperclass;
        String methodTextForSubclass;
        String confirmMessage;

        static PushDownPlan abort(String title, String message, int type) {
            PushDownPlan p = new PushDownPlan();
            p.aborted = true;
            p.abortTitle = title;
            p.abortMessage = message;
            p.abortMessageType = type;
            return p;
        }
    }

    private PushDownPlan buildPushDownPlan(PsiFile psiFile, String methodName, String targetSubclassName) {
        PsiMethod methodInSuperclass = resolveMethodByName(psiFile, methodName);
        if (methodInSuperclass == null) {
            return PushDownPlan.abort("CloneGuard",
                    "Could not find method " + methodName + "() in this file. The file may have changed since this " +
                    "result was shown — try re-scanning.",
                    JOptionPane.WARNING_MESSAGE);
        }

        PsiClass superClass = methodInSuperclass.getContainingClass();
        if (superClass == null) {
            return PushDownPlan.abort("CloneGuard",
                    "Could not resolve the containing class for " + methodName + "(). No changes were made.",
                    JOptionPane.WARNING_MESSAGE);
        }

        PsiClass targetSubclass = null;
        for (PsiClass candidate : PsiTreeUtil.findChildrenOfType(psiFile, PsiClass.class)) {
            if (candidate.getName() != null && candidate.getName().equals(targetSubclassName)) {
                targetSubclass = candidate;
                break;
            }
        }
        if (targetSubclass == null) {
            return PushDownPlan.abort("CloneGuard",
                    "Could not find a class named " + targetSubclassName + " in this file. No changes were made.",
                    JOptionPane.WARNING_MESSAGE);
        }

        // Safety measure #1: targetSubclass must actually be a DIRECT
        // subclass of the class that currently declares the method.
        PsiClass targetsSuper = targetSubclass.getSuperClass();
        if (targetsSuper == null || !targetsSuper.equals(superClass)) {
            return PushDownPlan.abort("CloneGuard — Not a Push Down Case",
                    targetSubclassName + " does not directly extend " + superClass.getName() + ", which is where " +
                    methodName + "() is currently declared. No changes were made.",
                    JOptionPane.WARNING_MESSAGE);
        }

        PsiCodeBlock body = methodInSuperclass.getBody();
        if (body == null) {
            return PushDownPlan.abort("CloneGuard",
                    methodName + "() has no body (e.g. abstract) — nothing to push down. No changes were made.",
                    JOptionPane.WARNING_MESSAGE);
        }

        // Safety measure #2 (the important one): search the WHOLE file for
        // any reference to this method from OUTSIDE targetSubclass —
        // including from other sibling subclasses, from the superclass
        // itself, or from any unrelated class. If anything else still
        // calls it, pushing it down would break that caller, since the
        // method would no longer be visible/inherited there. Uses
        // IntelliJ's own reference search rather than a hand-rolled text
        // scan, so it correctly finds calls through a superclass-typed
        // variable too, not just literal textual matches.
        List<PsiReference> externalReferences = new ArrayList<>();
        for (PsiReference ref : com.intellij.psi.search.searches.ReferencesSearch.search(methodInSuperclass).findAll()) {
            PsiElement refElement = ref.getElement();
            PsiClass refOwningClass = PsiTreeUtil.getParentOfType(refElement, PsiClass.class);
            boolean isInsideTargetSubclass = refOwningClass != null
                    && (refOwningClass.equals(targetSubclass) || PsiTreeUtil.isAncestor(targetSubclass, refOwningClass, false));
            boolean isInsideTheMethodItself = PsiTreeUtil.isAncestor(methodInSuperclass, refElement, false);
            if (!isInsideTargetSubclass && !isInsideTheMethodItself) {
                externalReferences.add(ref);
            }
        }
        if (!externalReferences.isEmpty()) {
            return PushDownPlan.abort("CloneGuard — Cannot Push Down Safely",
                    methodName + "() is still referenced from outside " + targetSubclassName + " (" +
                    externalReferences.size() + " other call site" + (externalReferences.size() == 1 ? "" : "s") +
                    " found). Pushing it down would break those callers, since it would no longer be inherited " +
                    "there. No changes were made.",
                    JOptionPane.WARNING_MESSAGE);
        }

        // Safety measure #3: targetSubclass must not already declare its
        // own method with this same signature — pushing down would
        // silently collide with (and likely shadow or conflict with) an
        // existing one.
        PsiParameter[] superParams = methodInSuperclass.getParameterList().getParameters();
        PsiType superReturn = methodInSuperclass.getReturnType();
        for (PsiMethod existing : targetSubclass.getMethods()) {
            if (existing.getName().equals(methodName)
                    && signaturesCompatible(existing.getParameterList().getParameters(), existing.getReturnType(), superParams, superReturn)) {
                return PushDownPlan.abort("CloneGuard — Cannot Push Down Safely",
                        targetSubclassName + " already declares its own " + methodName + "() with a matching " +
                        "signature. Pushing down would collide with it. No changes were made.",
                        JOptionPane.WARNING_MESSAGE);
            }
        }

        String confirmMessage =
                "CloneGuard analysis: " + methodName + "() is declared on " + superClass.getName() + " but is only " +
                "ever used from " + targetSubclassName + ".\n\n" +
                "Proposed refactoring (Push Down Method):\n" +
                "  • " + methodName + "() moves out of " + superClass.getName() + "\n" +
                "  • It's added directly to " + targetSubclassName + " instead\n" +
                "  • Other subclasses of " + superClass.getName() + " no longer inherit it\n\n" +
                "Apply this refactoring now?";

        PushDownPlan plan = new PushDownPlan();
        plan.aborted = false;
        plan.superClass = superClass;
        plan.targetSubclass = targetSubclass;
        plan.methodInSuperclass = methodInSuperclass;
        plan.methodTextForSubclass = methodInSuperclass.getText();
        plan.confirmMessage = confirmMessage;
        return plan;
    }

}
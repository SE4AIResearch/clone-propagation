package com.cloneguard.services;

import com.cloneguard.model.*;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.*;
import java.util.regex.*;

@Service(Service.Level.PROJECT)
public final class FileScannerService {

    private static final Logger LOG = Logger.getInstance(FileScannerService.class);
    private final Project project;

    public FileScannerService(Project project) {
        this.project = project;
    }

    /**
     * NEW (project-wide scan support): scans every Java file in the
     * project TOGETHER, rather than one file in isolation via
     * scanFile() above. "Together" here means each file is still sent
     * to the detection server as its own individual request -- clone
     * detection is inherently a within-file operation in this codebase
     * (see scanFile()'s own doc: functions extracted from ONE psiFile),
     * and that scope was a deliberate choice made earlier in this
     * project, not something this method changes. What "together" means
     * concretely is that the RESULTS are gathered and returned as one
     * combined project-wide picture, rather than requiring the user to
     * manually run Scan Current File once per file and lose the
     * cross-file overview in between.
     *
     * Returns a map from each scanned PsiFile to whatever clone groups
     * scanFile() found in it -- files with zero clones are still present
     * in the map with an empty list, so a caller can tell "scanned,
     * found nothing" apart from "wasn't scanned at all" (e.g. a file
     * with fewer than 2 functions, which scanFile() already skips).
     */
    /**
     * Combined result for one file from a project-wide scan: both its
     * clone groups AND its Push Down candidates, mirroring exactly what
     * ScanFileAction already gathers per-file (scanFile() +
     * findPushDownCandidates() called together) -- scanProject() should
     * produce the same combined picture per file, just looped across
     * every file in the project instead of one at a time.
     */
    public static class FileScanResult {
        public final List<CloneGroup> cloneGroups;
        public final List<PushDownCandidate> pushDownCandidates;

        public FileScanResult(List<CloneGroup> cloneGroups, List<PushDownCandidate> pushDownCandidates) {
            this.cloneGroups = cloneGroups;
            this.pushDownCandidates = pushDownCandidates;
        }
    }

    public Map<PsiFile, FileScanResult> scanProject() {
        Map<PsiFile, FileScanResult> results = new LinkedHashMap<>();
        Collection<VirtualFile> javaFiles = FileTypeIndex.getFiles(
                com.intellij.ide.highlighter.JavaFileType.INSTANCE,
                GlobalSearchScope.projectScope(project));

        LOG.info("CloneGuard: project-wide scan starting, " + javaFiles.size() + " Java file(s) found");

        int fileIndex = 0;
        for (VirtualFile vf : javaFiles) {
            fileIndex++;
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) continue;
            LOG.info("CloneGuard: project scan " + fileIndex + "/" + javaFiles.size() + " — " + psiFile.getName());

            // FIX (found live, testing this session): the original
            // version of this method only called scanFile() -- clone-pair
            // detection -- and never called findPushDownCandidates() at
            // all, unlike ScanFileAction (the single-file scan), which
            // already correctly calls both. Confirmed directly: a project
            // scan against a file with a genuine, real Push Down
            // candidate (a method on a superclass used by only one
            // subclass) silently found nothing for it, while the same
            // file's Pull Up-shaped clone was correctly detected. Both
            // checks now run for every file, matching ScanFileAction's
            // existing combined behavior exactly.
            List<CloneGroup> groups = scanFile(psiFile);
            List<PushDownCandidate> pushDownCandidates = findPushDownCandidates(psiFile);
            results.put(psiFile, new FileScanResult(groups, pushDownCandidates));
        }

        int totalClones = results.values().stream().mapToInt(r -> r.cloneGroups.size()).sum();
        int totalPushDown = results.values().stream().mapToInt(r -> r.pushDownCandidates.size()).sum();
        LOG.info("CloneGuard: project-wide scan complete — " + results.size() + " file(s) scanned, "
                + totalClones + " total clone group(s), " + totalPushDown + " total push-down candidate(s) found across the project");

        return results;
    }

    public List<CloneGroup> scanFile(PsiFile psiFile) {
        Map<String, String> functions = extractFunctions(psiFile);
        LOG.info("CloneGuard scan: found " + functions.size() + " functions in " + psiFile.getName());

        if (functions.size() < 2) return Collections.emptyList();

        // ── Send ALL functions to /scan in one call ───────────────────────────
        // The server handles Layer 1 (Type 1/2) and Layer 2 (Type 3/4) internally.
        // This avoids the pairing bugs caused by the old manual loop + /check approach.
        PythonServerClient client = project.getService(PythonServerClient.class);
        List<CloneGroup> serverGroups = new ArrayList<>();
        if (client != null && client.isServerAlive()) {
            LOG.info("CloneGuard: server alive, using /scan endpoint");
            serverGroups = client.scanFile(functions, psiFile.getName());
            LOG.info("CloneGuard scan: " + serverGroups.size() + " clone groups found via /scan");
        } else {
            // ── Fallback: server not available, run Layer 1 locally only ─────
            LOG.info("CloneGuard: server not available, falling back to local Layer 1 only");
            serverGroups = runLocalLayer1(functions);
        }

        // FIX (found live, this session -- extensive small/medium/large,
        // multi-domain testing): the server's Layer 2 similarity model
        // was found to consistently miss Type 3 near-miss clones across
        // every single example tested -- five independent pairs, three
        // sizes, five different domains, zero detections. Type 1, Type 2,
        // and (for the specific GCD-family shape) Type 4 all detected
        // reliably via the server; Type 3 specifically never did,
        // regardless of code size or how large the shared block was
        // relative to the one added guard-clause statement. Rather than
        // depend on a model behavior that can't be inspected or retrained
        // from here, this adds a LOCAL, deterministic Type 3 check that
        // runs unconditionally (server available or not) and merges its
        // findings in alongside whatever the server already found --
        // catching the exact "identical logic plus one added leading
        // guard clause" shape directly via structural comparison, with no
        // dependency on any similarity threshold at all. See
        // findLocalNearMissClones() below for the actual comparison.
        List<CloneGroup> localType3Groups = findLocalNearMissClones(functions, serverGroups);
        if (!localType3Groups.isEmpty()) {
            LOG.info("CloneGuard: local Type 3 check found " + localType3Groups.size()
                    + " additional near-miss clone group(s) the server missed");
        }

        List<CloneGroup> combined = new ArrayList<>(serverGroups);
        combined.addAll(localType3Groups);
        return combined;
    }

    /**
     * Deterministic, local Type 3 (near-miss) detector: for every pair of
     * functions NOT already covered by serverGroups, checks whether one
     * function's body is EXACTLY the other function's body with a single
     * leading guard-clause if-statement added (the specific, common shape
     * this project's own testing found the server's model consistently
     * misses -- see the FIX note in scanFile() above). Uses real
     * paren/brace-depth counting (stripLeadingGuardClause() below) rather
     * than regex, since guard-clause conditions routinely contain their
     * own nested parens (e.g. "s == null || s.isEmpty()") that a naive
     * "[^)]*"-style pattern cannot correctly bound.
     * <p>
     * Deliberately conservative in scope: only catches the SINGLE-leading-
     * guard-clause shape specifically, not general near-miss detection --
     * a full local reimplementation of Layer 2 is out of scope here. This
     * is a targeted fix for a specific, confirmed, reproducible gap, not
     * a replacement for the server's broader semantic matching.
     */
    private List<CloneGroup> findLocalNearMissClones(Map<String, String> functions, List<CloneGroup> alreadyFound) {
        List<CloneGroup> results = new ArrayList<>();
        Set<String> alreadyPaired = new HashSet<>();
        for (CloneGroup g : alreadyFound) {
            if (g.methods != null && g.methods.size() >= 2) {
                alreadyPaired.add(pairKey(g.methods.get(0), g.methods.get(1)));
            }
        }

        List<String> names = new ArrayList<>(functions.keySet());
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                String nameA = names.get(i);
                String nameB = names.get(j);
                if (alreadyPaired.contains(pairKey(nameA, nameB))) continue;

                String bodyA = functions.get(nameA);
                String bodyB = functions.get(nameB);

                if (isNearMissViaGuardClause(bodyA, bodyB)) {
                    results.add(new CloneGroup(CloneType.TYPE_3, 0.9,
                            List.of(nameA, nameB),
                            "Detected via local near-miss check (server did not classify this pair)"));
                    alreadyPaired.add(pairKey(nameA, nameB));
                }
            }
        }
        return results;
    }

    private String pairKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + "||" + b : b + "||" + a;
    }

    /**
     * True if bodyA and bodyB are identical (ignoring all whitespace,
     * and ignoring each method's own signature) once a single leading
     * guard-clause if-statement is stripped from whichever one has it.
     * Tries both directions -- either function could be "the safe one".
     * <p>
     * FIX (found live, this session -- confirmed via direct testing
     * immediately after the first version of this check landed):
     * extractFunctions() stores each function's FULL text via
     * method.getText() -- signature included ("public Character
     * lastCharSafe(String s) { ... }"), not just the "{ ... }" body
     * block alone. The first version of this check assumed body.trim()
     * would start with '{' and returned null immediately whenever it
     * didn't -- which was EVERY real call, since two different methods
     * always have different names/return types in their signature, so
     * the check silently never fired at all. Fixed by locating the
     * first '{' explicitly (the real start of the body, regardless of
     * whatever signature precedes it) before doing anything else, and
     * comparing only the BODY portion after that point -- the two
     * methods' signatures are expected to differ (that's normal, not
     * disqualifying) and are deliberately excluded from the comparison.
     */
    private boolean isNearMissViaGuardClause(String methodTextA, String methodTextB) {
        String bodyOnlyA = bodyPortion(methodTextA);
        String bodyOnlyB = bodyPortion(methodTextB);
        if (bodyOnlyA == null || bodyOnlyB == null) return false;

        String strippedA = stripLeadingGuardClauseFromBody(bodyOnlyA);
        if (strippedA != null
                && stripAllWhitespace(strippedA).equals(stripAllWhitespace(bodyOnlyB))
                && !isTrivialSameClassDelegation(bodyOnlyB)) {
            return true;
        }
        String strippedB = stripLeadingGuardClauseFromBody(bodyOnlyB);
        if (strippedB != null
                && stripAllWhitespace(strippedB).equals(stripAllWhitespace(bodyOnlyA))
                && !isTrivialSameClassDelegation(bodyOnlyA)) {
            return true;
        }
        return false;
    }

    /**
     * True if body is a single bare, unqualified method call ("return
     * coreXxx(args);" or "coreXxx(args);" -- no receiver object before
     * the method name). This is EXACTLY the shape Extract Method leaves
     * behind on a method's non-guard-clause remainder after a
     * successful refactor -- a thin call to the newly-extracted shared
     * helper.
     * <p>
     * FIX (found live, this session -- confirmed via direct testing):
     * without this check, a Type 3 pair that had ALREADY been correctly
     * refactored kept getting flagged again on every subsequent scan,
     * since the guard-clause SHAPE is still structurally present after
     * refactoring (the safe method still has its guard clause, just now
     * calling the extracted helper instead of duplicating the raw
     * logic) -- creating an infinite "refactor it, scan again, it's
     * still there, refactor it again" loop the user hit directly.
     * <p>
     * Deliberately requires NO receiver object (no "."), which is what
     * distinguishes "already delegated to a same-class helper" from a
     * genuine single-statement duplicate that happens to also be one
     * method call -- e.g. "s.charAt(s.length()-1)" (a real, unrefactored
     * duplicate calling a JDK method with an explicit receiver "s.")
     * still correctly matches and gets flagged; only a bare, receiver-
     * less call like "coreLastChar(s)" is treated as already-delegated.
     */
    private boolean isTrivialSameClassDelegation(String bodyOnly) {
        String inner = bodyOnly.trim();
        if (inner.startsWith("{") && inner.endsWith("}")) {
            inner = inner.substring(1, inner.length() - 1).trim();
        }
        return Pattern.compile("^(return\\s+)?[A-Za-z_]\\w*\\([^;{}]*\\);$").matcher(inner).matches();
    }

    /** Returns just the "{ ... }" portion of a full method text, starting at its first '{'. */
    private String bodyPortion(String fullMethodText) {
        String trimmed = fullMethodText.trim();
        int braceIdx = trimmed.indexOf('{');
        return (braceIdx == -1) ? null : trimmed.substring(braceIdx);
    }

    private String stripAllWhitespace(String s) {
        return s.replaceAll("\\s+", "");
    }

    /**
     * If bodyOnly (already isolated to just its "{ ... }" block, via
     * bodyPortion() above -- NOT the full method text with signature)
     * starts with "{ if (<condition>) { <ifBody> } <rest> }", returns
     * "{ <rest> }". Returns null if bodyOnly doesn't start with exactly
     * this shape. Uses manual paren/brace-depth counting throughout --
     * NOT regex -- since the guard condition itself commonly contains
     * nested parens (method calls like s.isEmpty(), arr.length == 0,
     * etc.) that a bounded character class like "[^)]*" cannot
     * correctly span; confirmed directly while prototyping this fix,
     * where a regex-based first attempt silently failed on exactly
     * this case.
     */
    private String stripLeadingGuardClauseFromBody(String bodyOnly) {
        String trimmed = bodyOnly.trim();
        if (!trimmed.startsWith("{")) return null;

        int pos = 1;
        while (pos < trimmed.length() && Character.isWhitespace(trimmed.charAt(pos))) pos++;
        if (pos + 2 > trimmed.length() || !trimmed.startsWith("if", pos)) return null;
        pos += 2;
        while (pos < trimmed.length() && Character.isWhitespace(trimmed.charAt(pos))) pos++;
        if (pos >= trimmed.length() || trimmed.charAt(pos) != '(') return null;

        int parenDepth = 0;
        while (pos < trimmed.length()) {
            char c = trimmed.charAt(pos);
            if (c == '(') parenDepth++;
            else if (c == ')') {
                parenDepth--;
                if (parenDepth == 0) { pos++; break; }
            }
            pos++;
        }
        while (pos < trimmed.length() && Character.isWhitespace(trimmed.charAt(pos))) pos++;
        if (pos >= trimmed.length() || trimmed.charAt(pos) != '{') return null;

        int braceDepth = 0;
        while (pos < trimmed.length()) {
            char c = trimmed.charAt(pos);
            if (c == '{') braceDepth++;
            else if (c == '}') {
                braceDepth--;
                if (braceDepth == 0) { pos++; break; }
            }
            pos++;
        }

        if (pos >= trimmed.length() || trimmed.charAt(trimmed.length() - 1) != '}') return null;
        String remainder = trimmed.substring(pos, trimmed.length() - 1).trim();
        return "{" + remainder + "}";
    }

    /**
     * Fallback local Layer 1 detection when server is unavailable.
     * Only detects Type 1 and Type 2 clones.
     */
    private List<CloneGroup> runLocalLayer1(Map<String, String> functions) {
        List<String> names  = new ArrayList<>(functions.keySet());
        List<String> bodies = new ArrayList<>(functions.values());
        List<CloneGroup> groups   = new ArrayList<>();
        Set<String> seenPairs     = new HashSet<>();
        Set<String> clones        = new HashSet<>();

        for (int i = 0; i < names.size(); i++) {
            if (clones.contains(names.get(i))) continue;
            com.cloneguard.detection.LocalCloneDetector detector =
                    new com.cloneguard.detection.LocalCloneDetector();
            for (int j = 0; j < names.size(); j++) {
                if (j != i) detector.indexFunction(names.get(j), bodies.get(j));
            }
            CloneResult result = detector.check(bodies.get(i));
            if (result.isClone) {
                String pairKey = names.get(i).compareTo(result.matchedFunction) < 0
                        ? names.get(i) + "||" + result.matchedFunction
                        : result.matchedFunction + "||" + names.get(i);
                if (!seenPairs.contains(pairKey)) {
                    groups.add(new CloneGroup(result.cloneType, result.similarity,
                            List.of(names.get(i), result.matchedFunction),
                            "Detected via " + result.layer));
                    seenPairs.add(pairKey);
                    clones.add(names.get(i));
                    clones.add(result.matchedFunction);
                }
            }
        }
        return groups;
    }

    public Map<String, String> extractFunctions(PsiFile psiFile) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            Collection<PsiMethod> methods = PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod.class);

            // FIX (found live, Pull Up Method testing): this map used to be
            // keyed by method.getName() alone. Two DIFFERENT methods
            // sharing the same simple name in DIFFERENT classes -- e.g.
            // Dog.describe() and Cat.describe(), exactly the shape Pull Up
            // Method exists to find, since sibling subclasses very commonly
            // share a method name -- silently collided in this
            // Map<String,String>, with the second one overwriting the
            // first. Confirmed live: a real Type 1 clone pair across
            // sibling subclasses never reached detection at all, because
            // only ONE of the two copies survived this map before
            // anything was even sent to Layer 1/2.
            //
            // Fix: only qualify a key with its containing class name
            // ("ClassName.methodName") when the plain simple name would
            // actually collide with another method somewhere else in the
            // file. Every method whose name is unique in the file keeps
            // the exact same unqualified key as before -- this is a no-op
            // for the overwhelming majority of files, and fully backward
            // compatible with every existing caller of this map (server
            // payloads, local Layer 1 fallback, indexing). See
            // ExtractMethodEngine.resolveMethodByName() for the matching
            // other half of this fix -- qualified names need a
            // class-aware lookup on the way back in, not just a
            // collision-safe key on the way out.
            java.util.Map<String, Integer> nameCounts = new java.util.HashMap<>();
            for (PsiMethod method : methods) {
                if (method.getBody() == null) continue;
                nameCounts.merge(method.getName(), 1, Integer::sum);
            }

            for (PsiMethod method : methods) {
                PsiCodeBlock body = method.getBody();
                if (body == null) continue;
                String key = method.getName();
                if (nameCounts.getOrDefault(key, 0) > 1) {
                    PsiClass containingClass = method.getContainingClass();
                    if (containingClass != null && containingClass.getName() != null) {
                        key = containingClass.getName() + "." + key;
                    }
                }
                // FIX (professor-flagged, confirmed live): previously sent
                // body.getText() -- body-only text, no modifiers/return
                // type/name -- as the function's code, with the real
                // signature only embedded in buildSignature() as the map
                // KEY, which the server never parses. server.py's
                // get_return_type_shared() correctly detects body-only
                // code (starts with "{") and returns "unknown" rather than
                // misfiring on it, but "unknown" == "unknown" still PASSES
                // the return-type compatibility check in
                // operations_compatible_shared(), silently disabling that
                // gate for every pair in every Scenario 2 scan, not just an
                // occasional edge case. method.getText() gives the full
                // signature + body, matching what
                // InlineSuggestionListener.indexFunctions() already does
                // correctly elsewhere in this codebase.
                result.put(key, method.getText());
            }
            if (!result.isEmpty()) {
                LOG.info("CloneGuard: PSI extracted " + result.size() + " methods");
                return result;
            }
        } catch (Exception e) {
            LOG.warn("CloneGuard: PSI extraction failed: " + e.getMessage());
        }
        LOG.info("CloneGuard: falling back to regex extraction");
        extractWithRegex(psiFile.getText(), result);
        LOG.info("CloneGuard: regex extracted " + result.size() + " methods");
        return result;
    }

    public void navigateTo(String functionName, Editor editor) {
        if (functionName == null || functionName.isBlank()) return;
        Document document = editor.getDocument();
        String text = document.getText();
        String searchName = functionName.contains("(")
                ? functionName.substring(0, functionName.indexOf("(")).trim()
                : functionName;
        int idx = text.indexOf(searchName);
        if (idx < 0) return;
        editor.getCaretModel().moveToOffset(idx);
        editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
    }

    private void extractWithRegex(String text, Map<String, String> out) {
        Pattern p = Pattern.compile(
            "(?:(?:public|private|protected|static|final|synchronized)\\s+)*" +
            "(?:[A-Za-z_][A-Za-z0-9_<>\\[\\]]*\\s+)" +
            "([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^)]*\\)\\s*(?:throws\\s+[A-Za-z_,\\s]+)?\\s*\\{",
            Pattern.MULTILINE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (Set.of("if","for","while","switch","class","interface","enum").contains(name)) continue;
            // FIX (same root cause as the PSI path above): previously
            // captured only the braced block (extractBracedBlock from the
            // opening brace onward), discarding the signature the regex
            // match itself already spans (from m.start() through the
            // opening brace). Now captures the full method text -- start
            // of the match through the matching closing brace -- so this
            // fallback path sends the same full-method format as the
            // primary PSI path, keeping the two extraction paths
            // consistent with each other.
            int closeBrace = findMatchingCloseBrace(text, m.end() - 1);
            if (closeBrace < 0) continue;
            String fullMethod = text.substring(m.start(), closeBrace + 1);
            if (fullMethod.length() > 3) out.put(name, fullMethod);
        }
    }

    private int findMatchingCloseBrace(String text, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    // ── Push Down Method candidate detection ──────────────────────────────
    // Unlike clone detection, this isn't looking for two duplicated
    // methods — it's looking for ONE method that's declared too high in a
    // class hierarchy: a method sitting on a superclass that (a) has at
    // least two direct subclasses in this file, and (b) is, per static
    // reference search, only ever actually used from ONE of them. That
    // combination is exactly the situation Push Down Method exists to fix.
    //
    // Deliberately excludes the case of a superclass with only ONE
    // subclass in the file: with just one subclass, "only used by one
    // subclass" isn't a meaningful signal on its own — it's just as likely
    // that the method genuinely belongs on the superclass and the file
    // simply doesn't happen to show a second subclass using it yet. This
    // requires there to be at least one OTHER sibling subclass that
    // provably does NOT use it, which is what actually indicates the
    // method is misplaced.
    public List<PushDownCandidate> findPushDownCandidates(PsiFile psiFile) {
        List<PushDownCandidate> candidates = new ArrayList<>();
        try {
            Collection<PsiClass> allClasses = PsiTreeUtil.findChildrenOfType(psiFile, PsiClass.class);

            for (PsiClass superClass : allClasses) {
                List<PsiClass> directSubclasses = new ArrayList<>();
                for (PsiClass maybeSub : allClasses) {
                    PsiClass sup = maybeSub.getSuperClass();
                    if (sup != null && sup.equals(superClass)) {
                        directSubclasses.add(maybeSub);
                    }
                }
                // Need at least two siblings — see method-level note above
                // for why one subclass alone isn't enough signal.
                if (directSubclasses.size() < 2) continue;

                for (PsiMethod method : superClass.getMethods()) {
                    if (method.getBody() == null) continue; // abstract/interface — nothing to push
                    if (method.isConstructor()) continue;

                    Set<PsiClass> usingSubclasses = new HashSet<>();
                    boolean usedOutsideSubclasses = false;

                    for (PsiReference ref : com.intellij.psi.search.searches.ReferencesSearch.search(method).findAll()) {
                        PsiElement refElement = ref.getElement();
                        // Ignore the method's own body (recursive self-calls
                        // don't count as "external usage").
                        if (PsiTreeUtil.isAncestor(method, refElement, false)) continue;

                        PsiClass refOwningClass = PsiTreeUtil.getParentOfType(refElement, PsiClass.class);
                        if (refOwningClass == null) {
                            usedOutsideSubclasses = true;
                            continue;
                        }
                        if (directSubclasses.contains(refOwningClass)) {
                            usingSubclasses.add(refOwningClass);
                        } else {
                            // Used from the superclass itself (by another
                            // method there), from an unrelated class, or
                            // from a subclass further down the hierarchy —
                            // any of these means it's not safely
                            // push-down-able to just one direct subclass.
                            usedOutsideSubclasses = true;
                        }
                    }

                    if (!usedOutsideSubclasses && usingSubclasses.size() == 1) {
                        PsiClass targetSubclass = usingSubclasses.iterator().next();
                        candidates.add(new PushDownCandidate(
                                method.getName(),
                                superClass.getName(),
                                targetSubclass.getName()
                        ));
                        LOG.info("CloneGuard: push-down candidate found: " + method.getName() +
                                "() on " + superClass.getName() + " -> " + targetSubclass.getName());
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("CloneGuard: findPushDownCandidates failed: " + e.getMessage());
        }
        return candidates;
    }
}
package com.cloneguard.services;

import com.cloneguard.detection.LocalCloneDetector;
import com.cloneguard.model.*;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
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

    public List<CloneGroup> scanFile(PsiFile psiFile) {
        Map<String, String> functions = extractFunctions(psiFile);
        LOG.info("CloneGuard scan: found " + functions.size() + " functions in " + psiFile.getName());

        if (functions.size() < 2) return Collections.emptyList();

        List<String> names = new ArrayList<>(functions.keySet());
        List<String> bodies = new ArrayList<>(functions.values());
        List<CloneGroup> groups = new ArrayList<>();
        Set<String> grouped = new HashSet<>();

        // Layer 1: Local detection for all pairs
        for (int i = 0; i < names.size(); i++) {
            if (grouped.contains(names.get(i))) continue;
            LocalCloneDetector detector = new LocalCloneDetector();
            for (int j = 0; j < names.size(); j++) {
                if (j != i) detector.indexFunction(names.get(j), bodies.get(j));
            }
            CloneResult result = detector.check(bodies.get(i));
            if (result.isClone) {
                LOG.info("[CloneGuard] Layer 1 match: " + names.get(i) + " -> " + result.matchedFunction);
                groups.add(new CloneGroup(result.cloneType, result.similarity,
                        List.of(names.get(i), result.matchedFunction), "Detected via " + result.layer));
                grouped.add(names.get(i));
                grouped.add(result.matchedFunction);
            }
        }
        LOG.info("[CloneGuard] After Layer 1, grouped=" + grouped);

        // Layer 2: Server detection
        PythonServerClient client = project.getService(PythonServerClient.class);
        if (client != null && client.isServerAlive()) {
            LOG.info("CloneGuard: server alive, running Layer 2");

            for (int i = 0; i < names.size(); i++) {
                if (grouped.contains(names.get(i))) {
                    LOG.info("[CloneGuard] Layer 2 skip (already grouped): " + names.get(i));
                    continue;
                }
                LOG.info("[CloneGuard] Layer 2 processing: " + names.get(i));

                // Reset and index only OTHER non-grouped functions WITH SAME RETURN TYPE
                // Excluding different return types prevents isPrime(boolean) from
                // being the best match when checking reverseString(String)
                client.resetIndex();
                String myReturn = extractReturnType(names.get(i));
                boolean indexedAny = false;
                for (int j = 0; j < names.size(); j++) {
                    if (j == i || grouped.contains(names.get(j))) continue;
                    String otherReturn = extractReturnType(names.get(j));
                    if (myReturn.isEmpty() || otherReturn.isEmpty() || myReturn.equals(otherReturn)) {
                        // Send full method (signature + body) for better CodeBERT embeddings
                        String fullMethod = names.get(j) + " " + bodies.get(j);
                        client.indexFunction(names.get(j), fullMethod);
                        indexedAny = true;
                    }
                }
                // Nothing to compare against — skip this function
                if (!indexedAny) {
                    LOG.info("[CloneGuard] Skipping " + names.get(i) + " — no same-return-type candidates. myReturn='" + myReturn + "' grouped=" + grouped);
                    continue;
                }

                // Send full method (signature + body) for better semantic matching
                String fullCandidate = names.get(i) + " " + bodies.get(i);
                CloneResult result = client.check(fullCandidate);
                if (result.isClone) {
                    // Extract matched function base name
                    String matchedRaw = result.matchedFunction;
                    String matchedBase = matchedRaw.contains("(")
                        ? matchedRaw.substring(0, matchedRaw.indexOf("(")).trim()
                        : matchedRaw.replace("()", "").trim();

                    // Find full signature of matched function
                    String matchedReturn = "";
                    String matchedFullName = "";
                    for (String n : names) {
                        String nBase = n.contains("(")
                            ? n.substring(0, n.indexOf("(")).trim() : n.trim();
                        if (nBase.equalsIgnoreCase(matchedBase)) {
                            matchedReturn = extractReturnType(n);
                            matchedFullName = n;
                            break;
                        }
                    }

                    // Block if return types are known and different
                    String currentReturn = extractReturnType(names.get(i));
                    if (!currentReturn.isEmpty() && !matchedReturn.isEmpty()
                            && !currentReturn.equals(matchedReturn)) {
                        LOG.info("[CloneGuard] Skipping — return types differ: "
                                + currentReturn + " vs " + matchedReturn);
                        continue;
                    }

                    groups.add(new CloneGroup(result.cloneType, result.similarity,
                            List.of(names.get(i), result.matchedFunction), "Detected via " + result.layer));
                    grouped.add(names.get(i));
                    if (!matchedFullName.isEmpty()) grouped.add(matchedFullName);
                }
            }
        } else {
            LOG.info("CloneGuard: server not available, Layer 2 skipped");
        }

        LOG.info("CloneGuard scan: " + groups.size() + " clone groups found");
        return groups;
    }

    public Map<String, String> extractFunctions(PsiFile psiFile) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            Collection<PsiMethod> methods = PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod.class);
            for (PsiMethod method : methods) {
                PsiCodeBlock body = method.getBody();
                if (body == null) continue;
                result.put(buildSignature(method), body.getText());
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

    /** Extracts return type from "methodName(params):returnType" → "returnType" */
    private String extractReturnType(String signature) {
        if (signature == null) return "";
        int colon = signature.lastIndexOf(':');
        if (colon >= 0 && colon < signature.length() - 1) {
            return signature.substring(colon + 1).trim();
        }
        return "";
    }

    private String buildSignature(PsiMethod method) {
        StringBuilder sb = new StringBuilder(method.getName()).append("(");
        PsiParameter[] params = method.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].getType().getPresentableText()).append(" ").append(params[i].getName());
        }
        sb.append(")");
        PsiType returnType = method.getReturnType();
        if (returnType != null) sb.append(":").append(returnType.getPresentableText());
        return sb.toString();
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
            String body = extractBracedBlock(text, m.end() - 1);
            if (body != null && body.length() > 3) out.put(name, body);
        }
    }

    private String extractBracedBlock(String text, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return text.substring(openBrace, i + 1); }
        }
        return null;
    }
}
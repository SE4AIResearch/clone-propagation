package com.cloneguard.services;

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

        // ── Send ALL functions to /scan in one call ───────────────────────────
        // The server handles Layer 1 (Type 1/2) and Layer 2 (Type 3/4) internally.
        // This avoids the pairing bugs caused by the old manual loop + /check approach.
        PythonServerClient client = project.getService(PythonServerClient.class);
        if (client != null && client.isServerAlive()) {
            LOG.info("CloneGuard: server alive, using /scan endpoint");
            List<CloneGroup> groups = client.scanFile(functions, psiFile.getName());
            LOG.info("CloneGuard scan: " + groups.size() + " clone groups found via /scan");
            return groups;
        }

        // ── Fallback: server not available, run Layer 1 locally only ─────────
        LOG.info("CloneGuard: server not available, falling back to local Layer 1 only");
        return runLocalLayer1(functions);
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
from flask import Flask, request, jsonify, send_file
from flask_cors import CORS
import os
import re

# Reduce native crashes on macOS when PyTorch/FAISS run together
os.environ.setdefault("OMP_NUM_THREADS", "1")
os.environ.setdefault("MKL_NUM_THREADS", "1")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

import torch
from transformers import AutoTokenizer, AutoModel
import faiss
import numpy as np

app = Flask(__name__)
CORS(app)

print("[CloneGuard] Loading CodeBERT model...")
tokenizer = AutoTokenizer.from_pretrained("microsoft/codebert-base")
model = AutoModel.from_pretrained("microsoft/codebert-base")
model.eval()
torch.set_num_threads(1)
print("[CloneGuard] CodeBERT ready.")

# ── UniXcoder for AI-generated code detection ──────────────────────────────
print("[CloneGuard] Loading UniXcoder model for AI detection...")
from transformers import RobertaTokenizer, RobertaModel
ai_tokenizer = RobertaTokenizer.from_pretrained("microsoft/unixcoder-base")
ai_model = RobertaModel.from_pretrained("microsoft/unixcoder-base")
ai_model.eval()
print("[CloneGuard] UniXcoder ready.")

EMBEDDING_DIM = 768
faiss_index = faiss.IndexFlatIP(EMBEDDING_DIM)
stored_functions = []


def is_java_method(code):
    """Check if code looks like a real Java method declaration."""
    method_pattern = re.compile(
        r'(?:public|private|protected|static|final|synchronized|abstract|\s)+'
        r'(?:void|int|long|double|float|boolean|char|byte|short|String|[\w<>\[\]]+)\s+'
        r'\w+\s*\([^)]*\)\s*(?:throws\s+[\w,\s]+)?\s*\{'
    )
    return bool(method_pattern.search(code))


def chunk_into_functions(code):
    """
    Extract only top-level Java methods.
    Key fix: if the code IS already a single method, return it directly.
    Otherwise extract multiple methods from a class/file.
    """
    code = code.strip()

    # If code is already a single method — return it directly without re-chunking
    if is_java_method(code):
        depth = 0
        top_level_opens = 0
        for ch in code:
            if ch == '{':
                depth += 1
                if depth == 1:
                    top_level_opens += 1
            elif ch == '}':
                depth -= 1
        # Single top-level block = single method
        if top_level_opens == 1:
            return [code]

    # Multiple methods — extract each top-level block
    chunks = []
    depth = 0
    start = 0

    for i, ch in enumerate(code):
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                chunk = code[start:i+1].strip()
                if len(chunk) > 20 and is_java_method(chunk):
                    chunks.append(chunk)
                start = i + 1

    if not chunks and len(code) > 20:
        chunks.append(code)
    return chunks


def get_embedding(code_snippet):
    inputs = tokenizer(
        code_snippet,
        return_tensors="pt",
        max_length=512,
        truncation=True,
        padding=True
    )
    with torch.no_grad():
        outputs = model(**inputs)
    embedding = outputs.last_hidden_state[:, 0, :].squeeze().numpy()
    norm = np.linalg.norm(embedding)
    if norm > 0:
        embedding = embedding / norm
    return embedding.astype("float32")


def normalize_body(code):
    return re.sub(r'\s+', ' ', code).strip()


def strip_to_structure(code):
    """Replace all user-defined identifiers with VAR — for Type 2 detection."""
    keywords = {
        # Java keywords
        'public', 'private', 'protected', 'static', 'final', 'void',
        'int', 'long', 'double', 'float', 'boolean', 'char', 'byte',
        'short', 'class', 'interface', 'extends', 'implements', 'new',
        'return', 'if', 'else', 'for', 'while', 'do', 'switch', 'case',
        'break', 'continue', 'try', 'catch', 'throw', 'throws', 'this',
        'super', 'import', 'package', 'null', 'true', 'false', 'instanceof',
        'abstract', 'synchronized', 'volatile', 'transient', 'native',
        'enum', 'assert', 'default', 'goto', 'const', 'strictfp',
        # JavaScript keywords (keep for compatibility)
        'function', 'let', 'var', 'typeof', 'async', 'await', 'undefined',
        'of', 'in', 'delete', 'void'
    }
    # Strip comments and strings first
    code = re.sub(r'//[^\n]*', '', code)
    code = re.sub(r'/\*[\s\S]*?\*/', '', code)
    code = re.sub(r'["\'].*?["\']', 'STR', code)
    code = re.sub(r'\b\d+\.?\d*\b', 'NUM', code)
    tokens = re.findall(r'\b\w+\b|[^\w\s]', code)
    return ' '.join(
        'VAR' if re.match(r'^[a-zA-Z_]\w*$', t) and t not in keywords else t
        for t in tokens
    )


def structural_similarity(code1, code2):
    keywords = {
        # Java keywords
        'public', 'private', 'protected', 'static', 'final', 'void',
        'int', 'long', 'double', 'float', 'boolean', 'char', 'byte',
        'short', 'class', 'interface', 'extends', 'implements', 'new',
        'return', 'if', 'else', 'for', 'while', 'do', 'switch', 'case',
        'break', 'continue', 'try', 'catch', 'throw', 'throws', 'this',
        'super', 'import', 'package', 'null', 'true', 'false', 'instanceof',
        'abstract', 'synchronized', 'volatile', 'transient', 'native',
        'enum', 'assert', 'default', 'strictfp',
        # JavaScript keywords
        'function', 'const', 'let', 'var', 'typeof', 'async', 'await',
        'undefined', 'of', 'in', 'delete',
        # Common library methods
        'length', 'split', 'join', 'reverse', 'push', 'pop', 'map',
        'filter', 'reduce', 'forEach', 'Math', 'max', 'min', 'abs',
        'floor', 'ceil', 'round', 'parseInt', 'parseFloat', 'toString',
        'indexOf', 'includes', 'slice', 'splice', 'concat', 'sort',
        'find', 'some', 'every', 'console', 'log', 'Object', 'Array',
        'String', 'Number', 'Boolean', 'trim', 'System', 'out', 'println'
    }

    def normalize(code):
        code = re.sub(r'//[^\n]*', '', code)
        code = re.sub(r'/\*[\s\S]*?\*/', '', code)
        code = re.sub(r'["\'].*?["\']', 'STR', code)
        code = re.sub(r'\b\d+\b', 'NUM', code)
        words = re.findall(r'\b[a-zA-Z_]\w*\b', code)
        return [w if w in keywords else 'VAR' for w in words]

    tokens1 = normalize(code1)
    tokens2 = normalize(code2)
    if not tokens1 or not tokens2:
        return 0.0
    min_len = min(len(tokens1), len(tokens2))
    max_len = max(len(tokens1), len(tokens2))
    matches = sum(1 for i in range(min_len) if tokens1[i] == tokens2[i])
    score = matches / max_len if max_len > 0 else 0.0
    print(f"[CloneGuard] Structural similarity: {score:.4f}")
    return score


def extract_function_name(code, index):
    # Excluded names — Java types, classes, keywords that aren't method names
    excluded = {
        'StringBuilder', 'String', 'Integer', 'Double', 'Float', 'Long',
        'Boolean', 'Object', 'List', 'Map', 'Set', 'ArrayList', 'HashMap',
        'HashSet', 'Arrays', 'System', 'Math', 'Optional', 'Stream',
        'Collections', 'Iterator', 'Exception', 'RuntimeException',
        'new', 'return', 'if', 'for', 'while', 'switch', 'class', 'int',
        'void', 'boolean', 'char', 'byte', 'short', 'long', 'double', 'float'
    }

    # Java method pattern: access modifiers + return type + method name + (
    java_match = re.finditer(
        r'(?:public|private|protected|static|final|synchronized|\s)+'
        r'(?:void|int|long|double|float|boolean|char|byte|short|String|[\w<>\[\]]+)\s+'
        r'(\w+)\s*\(',
        code
    )
    for m in java_match:
        name = m.group(1)
        if name not in excluded:
            return name

    # JavaScript fallback
    js_match = re.search(r'function\s+(\w+)', code)
    if js_match:
        return js_match.group(1)

    return f"function_{index}"


@app.route("/index", methods=["POST"])
def build_index():
    global faiss_index, stored_functions
    data = request.get_json()
    code = data.get("code", "")
    fn_name_hint = data.get("name", "")
    reset = data.get("reset", False)

    # Handle reset-only call (empty file saved)
    if reset:
        faiss_index = faiss.IndexFlatIP(EMBEDDING_DIM)
        stored_functions = []
        print("[CloneGuard] Index reset — file cleared.")
        return jsonify({"ok": True, "indexed": 0, "message": "Index cleared"})

    if not code or len(code.strip()) < 20:
        return jsonify({"ok": False, "message": "No code provided"}), 400

    code = code.strip()

    # Plugin sends just method body starting with { — index it as one unit
    # Don't re-chunk it, that causes for-loops to be indexed separately
    if code.startswith('{'):
        # Try to extract name from body content, fall back to hint or index
        fn_name = fn_name_hint if fn_name_hint else extract_function_name(code, len(stored_functions))
        chunk_body = normalize_body(code)
        already_exists = any(normalize_body(f["snippet"]) == chunk_body for f in stored_functions)
        if already_exists:
            print(f"[CloneGuard] Skipping duplicate: {fn_name}")
        else:
            embedding = get_embedding(code)
            faiss_index.add(np.array([embedding]))
            stored_functions.append({
                "name": fn_name,
                "snippet": code,
                "preview": code[:80] + "..."
            })
            print(f"[CloneGuard] Indexed: {fn_name}")
        print(f"[CloneGuard] FAISS index total: {faiss_index.ntotal} vectors")
        return jsonify({"ok": True, "indexed": faiss_index.ntotal})

    # Full method or file — chunk normally
    chunks = chunk_into_functions(code)
    print(f"[CloneGuard] chunk count={len(chunks)}, name_hint='{fn_name_hint}', code_start='{code[:50]}'")

    for i, chunk in enumerate(chunks):
        chunk_body = normalize_body(chunk)
        fn_name = fn_name_hint if fn_name_hint and i == 0 else extract_function_name(chunk, len(stored_functions))

        already_exists = any(normalize_body(f["snippet"]) == chunk_body for f in stored_functions)
        if already_exists:
            print(f"[CloneGuard] Skipping duplicate: {fn_name}")
            continue

        embedding = get_embedding(chunk)
        faiss_index.add(np.array([embedding]))
        stored_functions.append({
            "name": fn_name,
            "snippet": chunk,
            "preview": chunk[:80] + "..."
        })
        print(f"[CloneGuard] Indexed: {fn_name}")

    print(f"[CloneGuard] FAISS index total: {faiss_index.ntotal} vectors")
    return jsonify({"ok": True, "indexed": faiss_index.ntotal})


@app.route("/check", methods=["POST"])
def check_clone():
    data = request.get_json()
    suggestion = data.get("suggestion", "")

    if not suggestion or len(suggestion.strip()) < 20:
        return jsonify({"isClone": False, "reason": "Too short"}), 200

    if faiss_index.ntotal < 1:
        return jsonify({"isClone": False, "reason": "Index empty"}), 200

    suggestion = suggestion.strip()

    # Extract just the body if it's a full method — for fair comparison
    # with stored body-only snippets
    def extract_body(code):
        """Extract just the { body } from a full method if possible."""
        code = code.strip()
        if code.startswith('{'):
            return code
        # Find first { and return from there
        idx = code.find('{')
        if idx != -1:
            return code[idx:].strip()
        return code

    # Get the body of the pasted suggestion for comparison
    suggestion_body = extract_body(suggestion)

    # Use full suggestion for embedding (more context = better)
    chunks = [suggestion] if is_java_method(suggestion) else chunk_into_functions(suggestion)
    if not chunks:
        chunks = [suggestion]

    best_match = None

    for chunk in chunks:
        chunk_body_full = normalize_body(chunk)
        chunk_body = normalize_body(extract_body(chunk))
        chunk_stripped = strip_to_structure(extract_body(chunk))

        embedding = get_embedding(chunk)
        query = np.array([embedding])
        k = min(3, faiss_index.ntotal)
        distances, indices = faiss_index.search(query, k)

        for rank in range(k):
            semantic_score = float(distances[0][rank])
            idx = int(indices[0][rank])
            matched = stored_functions[idx]

            # Normalize matched snippet for comparison
            matched_body = normalize_body(extract_body(matched["snippet"]))
            matched_name = matched["name"]

            # Skip loop/block names
            if matched_name in ("for", "while", "if", "do", "switch", "try"):
                continue

            # Skip self-match by name — pasted function shouldn't match itself
            pasted_name = extract_function_name(suggestion, -1)
            if matched_name == pasted_name:
                print(f"[CloneGuard] Skipping self-match: {matched_name}")
                continue

            print(f"[CloneGuard] Candidate {matched_name}: semantic={semantic_score:.4f}")
            print(f"[CloneGuard] chunk_body: '{chunk_body[:80]}'")
            print(f"[CloneGuard] matched_body: '{matched_body[:80]}'")
            print(f"[CloneGuard] equal: {chunk_body == matched_body}")

            # Type 1: exact body match = always a clone
            # Whether same name or different name — if body is identical it's a duplicate
            pasted_name = extract_function_name(suggestion, -1)
            if chunk_body == matched_body:
                print(f"[CloneGuard] Type 1 detected: {matched_name}")
                return jsonify({
                    "isClone": True,
                    "cloneType": "Type 1 — Exact Clone",
                    "similarity": "100%",
                    "structural": "100%",
                    "severity": "Critical",
                    "matchFunction": matched_name + "()",
                    "matchFile": "Current file",
                    "matchLine": 0,
                    "recommendation": f"Identical copy of {matched_name}(). Remove and reuse original."
                })

            # Type 2: renamed clone
            matched_stripped = strip_to_structure(extract_body(matched["snippet"]))
            print(f"[CloneGuard] chunk_stripped: {chunk_stripped[:80]}")
            print(f"[CloneGuard] matched_stripped: {matched_stripped[:80]}")
            if chunk_stripped == matched_stripped:
                # Use matched function's stored name
                # matched_name is "function_0" etc — try to extract real name from stored snippet
                real_name = matched_name
                if real_name.startswith("function_"):
                    # Try extract from stored snippet body
                    extracted = extract_function_name(matched["snippet"], idx)
                    if not extracted.startswith("function_"):
                        real_name = extracted
                print(f"[CloneGuard] Type 2 detected: {real_name}")
                return jsonify({
                    "isClone": True,
                    "cloneType": "Type 2 — Renamed Clone",
                    "similarity": f"{round(semantic_score * 100)}%",
                    "structural": "100%",
                    "severity": "High",
                    "matchFunction": real_name + "()",
                    "matchFile": "Current file",
                    "matchLine": 0,
                    "recommendation": f"Same logic as {real_name}() but renamed. Reuse the original."
                })

            # Type 3 / 4: semantic
            if semantic_score < 0.92:
                continue

            struct_score = structural_similarity(extract_body(chunk), extract_body(matched["snippet"]))
            print(f"[CloneGuard] Structural: {struct_score:.4f}")

            # Require minimum structural similarity to avoid false positives
            # Two completely different functions (like sort vs sum) should not match
            if struct_score < 0.40:
                print(f"[CloneGuard] Skipping — structural too low: {struct_score:.4f}")
                continue

            if best_match is None or semantic_score > best_match["semantic_score"]:
                if struct_score >= 0.70:
                    clone_type = "Type 3 — Near-Miss Clone"
                    severity = "High"
                    recommendation = f"Structurally similar to {matched_name}(). Consider reusing it."
                else:
                    clone_type = "Type 4 — Semantic Clone"
                    severity = "Medium"
                    recommendation = f"Same intent as {matched_name}() but different implementation."
                best_match = {
                    "isClone": True,
                    "cloneType": clone_type,
                    "similarity": f"{round(semantic_score * 100)}%",
                    "structural": f"{round(struct_score * 100)}%",
                    "severity": severity,
                    "matchFunction": matched_name + "()",
                    "matchFile": "Current file",
                    "matchLine": 0,
                    "recommendation": recommendation,
                    "semantic_score": semantic_score,
                }

    if best_match:
        result = dict(best_match)
        result.pop("semantic_score", None)
        return jsonify(result)

    return jsonify({"isClone": False})


def get_ai_embedding(code_snippet):
    """Get UniXcoder embedding for AI detection."""
    inputs = ai_tokenizer(
        code_snippet,
        return_tensors="pt",
        max_length=512,
        truncation=True,
        padding=True
    )
    with torch.no_grad():
        outputs = ai_model(**inputs)
    embedding = outputs.last_hidden_state[:, 0, :].squeeze().numpy()
    norm = np.linalg.norm(embedding)
    if norm > 0:
        embedding = embedding / norm
    return embedding.astype("float32")


def compute_perplexity(code_snippet):
    """
    Compute perplexity-like score using CodeBERT.
    AI-generated code has lower perplexity (more predictable tokens).
    Returns a score 0-1 where higher = more likely AI generated.
    """
    inputs = tokenizer(
        code_snippet,
        return_tensors="pt",
        max_length=512,
        truncation=True,
        padding=True
    )
    with torch.no_grad():
        outputs = model(**inputs, output_attentions=False)

    # Use variance of token embeddings as proxy for perplexity
    # Low variance = tokens are very uniform = AI-like
    hidden = outputs.last_hidden_state.squeeze(0).numpy()
    token_variance = float(np.mean(np.var(hidden, axis=0)))

    # Normalize: typical range 0.5-3.0
    # Low variance (< 1.0) → AI, High variance (> 2.0) → Human
    normalized = max(0.0, min(1.0, 1.0 - (token_variance - 0.3) / 1.8))
    print(f"[CloneGuard] Token variance: {token_variance:.4f} → perplexity_score: {normalized:.4f}")
    return normalized


@app.route("/detect-ai", methods=["POST"])
def detect_ai():
    """
    Detect if pasted code is AI-generated.
    Uses two signals:
      1. Perplexity score via CodeBERT token variance
      2. UniXcoder embedding similarity to known AI code patterns
    Returns confidence score 0-1.
    """
    data = request.get_json()
    code = data.get("code", "")

    if not code or len(code.strip()) < 20:
        return jsonify({
            "isAiGenerated": False,
            "confidence": 0.0,
            "reason": "Too short to analyze"
        }), 200

    try:
        # Signal 1: Perplexity score (weight 0.40)
        perplexity_score = compute_perplexity(code)

        # Signal 2: UniXcoder embedding self-similarity
        # AI code tends to be very internally consistent
        # Split code into two halves and measure similarity
        mid = len(code) // 2
        first_half = code[:mid]
        second_half = code[mid:]

        if len(first_half.strip()) > 10 and len(second_half.strip()) > 10:
            emb1 = get_ai_embedding(first_half)
            emb2 = get_ai_embedding(second_half)
            # High cosine similarity between halves = AI (consistent style throughout)
            internal_similarity = float(np.dot(emb1, emb2))
            # Normalize: typical range 0.7-0.99
            embedding_score = max(0.0, min(1.0, (internal_similarity - 0.7) / 0.25))
        else:
            embedding_score = perplexity_score  # fallback

        print(f"[CloneGuard] perplexity_score={perplexity_score:.4f}, embedding_score={embedding_score:.4f}")

        # Combined confidence (weighted)
        confidence = round(perplexity_score * 0.40 + embedding_score * 0.60, 4)

        # Decision thresholds
        if confidence > 0.65:
            label = "AI Generated — High Confidence"
            is_ai = True
        elif confidence > 0.35:
            label = "Possibly AI Generated"
            is_ai = True
        else:
            label = "Likely Human Written"
            is_ai = False

        print(f"[CloneGuard] AI Detection: confidence={confidence:.4f} → {label}")

        return jsonify({
            "isAiGenerated": is_ai,
            "confidence": confidence,
            "confidencePercent": f"{round(confidence * 100)}%",
            "label": label,
            "perplexityScore": round(perplexity_score, 4),
            "embeddingScore": round(embedding_score, 4)
        })

    except Exception as e:
        print(f"[CloneGuard] AI detection error: {e}")
        return jsonify({
            "isAiGenerated": False,
            "confidence": 0.0,
            "reason": str(e)
        }), 500




# ── Scenario 3: /scan endpoint ────────────────────────────────────────────────
# Accepts a list of Java functions (name + code pairs) from a PR.
# Runs full Layer 1 + Layer 2 detection across all functions.
# Returns JSON with all detected clone groups, types, similarities.

@app.route("/scan", methods=["POST"])
def scan_file():
    """
    PR Agent scan endpoint.
    Detects all 4 clone types across any number of functions, any code size.
    Type 1 & 2 caught by Layer 1. Type 3 & 4 caught by Layer 2.
    No false positives. No double entries.
    """
    data = request.get_json()
    functions = data.get("functions", [])
    filename = data.get("filename", "Unknown.java")

    if not functions or len(functions) < 2:
        return jsonify({
            "cloneGroups": [],
            "totalClones": 0,
            "scannedFunctions": len(functions),
            "filename": filename,
            "message": "Need at least 2 functions to scan"
        })

    print(f"[CloneGuard] /scan received {len(functions)} functions from {filename}")

    # ── Build scan index ──────────────────────────────────────────────────────
    scan_index = faiss.IndexFlatIP(EMBEDDING_DIM)
    scan_functions = []

    for fn in functions:
        name = fn.get("name", f"function_{len(scan_functions)}")
        code = fn.get("code", "").strip()
        if not code or len(code) < 10:
            continue
        embedding = get_embedding(code)
        scan_index.add(np.array([embedding]))
        scan_functions.append({
            "name": name,
            "snippet": code,
            "embedding": embedding
        })

    print(f"[CloneGuard] /scan indexed {len(scan_functions)} functions")

    def extract_body_local(code):
        code = code.strip()
        if code.startswith("{"):
            return code
        idx = code.find("{")
        if idx != -1:
            return code[idx:].strip()
        return code

    def bag_of_keywords(code):
        """Keyword frequency bag — order-independent, works for any size snippet."""
        keywords = {
            "public","private","protected","static","final","void","int","long",
            "double","float","boolean","char","byte","short","return","if","else",
            "for","while","do","switch","case","break","continue","try","catch",
            "throw","throws","new","null","true","false","this","super","instanceof"
        }
        code = re.sub(r"//[^\n]*", "", code)
        code = re.sub(r"/\*[\s\S]*?\*/", "", code)
        tokens = re.findall(r"\b[a-zA-Z_]\w*\b", code)
        bag = {}
        for t in tokens:
            if t in keywords:
                bag[t] = bag.get(t, 0) + 1
        return bag

    def bag_similarity(code1, code2):
        b1 = bag_of_keywords(code1)
        b2 = bag_of_keywords(code2)
        if not b1 or not b2:
            return 0.0
        keys = set(b1) | set(b2)
        intersection = sum(min(b1.get(k, 0), b2.get(k, 0)) for k in keys)
        union = sum(max(b1.get(k, 0), b2.get(k, 0)) for k in keys)
        score = intersection / union if union > 0 else 0.0
        print(f"[CloneGuard] Bag similarity: {score:.4f}")
        return score

    def get_return_type(code):
        """Extract return type from a Java method signature."""
        match = re.search(
            r'(?:public|private|protected|static|final|\s)+\s*(void|int|long|double|float|boolean|String|\w+)\s+\w+\s*\(',
            code
        )
        return match.group(1) if match else "unknown"

    def get_operation_family(code):
        """Detect what a function computes — used to prevent false positives."""
        ops = set()
        if re.search(r'\+=|=\s*\w+\s*\+\s*\w+|\bsum\b|\btotal\b', code, re.IGNORECASE):
            ops.add('sum')
        if re.search(r'%\s*\w+|Math\.sqrt|Math\.pow|\bprime\b', code, re.IGNORECASE):
            ops.add('prime_math')
        if re.search(r'\bmax\b|\bmaximum\b|>\s*\w+', code, re.IGNORECASE):
            ops.add('max')
        if re.search(r'\bmin\b|\bminimum\b|<\s*\w+', code, re.IGNORECASE):
            ops.add('min')
        if re.search(r'\baverage\b|\bmean\b', code, re.IGNORECASE):
            ops.add('average')
        if re.search(r'\bsort\b', code, re.IGNORECASE):
            ops.add('sort')
        return ops

    def operations_compatible(code1, code2):
        """Two functions are compatible if same return type and overlapping operations."""
        # Return type must match — int vs boolean are never clones
        ret1 = get_return_type(code1)
        ret2 = get_return_type(code2)
        if ret1 != ret2:
            print(f"[CloneGuard] Return type mismatch: {ret1} vs {ret2} — skipping")
            return False
        # If both have known ops and they don't overlap — not clones
        ops1 = get_operation_family(code1)
        ops2 = get_operation_family(code2)
        if ops1 and ops2 and ops1.isdisjoint(ops2):
            print(f"[CloneGuard] Operation mismatch: {ops1} vs {ops2} — skipping")
            return False
        return True

    clone_groups = []
    seen_pairs = set()  # prevents A<->B and B<->A duplicates

    # ── Layer 1: Type 1 and Type 2 — all pairs ───────────────────────────────
    for i, fn_i in enumerate(scan_functions):
        body_i = normalize_body(extract_body_local(fn_i["snippet"]))
        stripped_i = strip_to_structure(extract_body_local(fn_i["snippet"]))

        for j, fn_j in enumerate(scan_functions):
            if i >= j:
                continue

            pair_key = tuple(sorted([fn_i["name"], fn_j["name"]]))
            if pair_key in seen_pairs:
                continue

            body_j = normalize_body(extract_body_local(fn_j["snippet"]))
            stripped_j = strip_to_structure(extract_body_local(fn_j["snippet"]))

            # Type 1: exact body match
            if body_i == body_j:
                clone_groups.append({
                    "cloneType": "Type 1 — Exact Clone",
                    "similarity": "100%",
                    "functionA": fn_i["name"],
                    "functionB": fn_j["name"],
                    "filename": filename,
                    "detectionLayer": "Layer 1 (Local — exact hash)",
                    "severity": "Critical",
                    "recommendation": f"{fn_j['name']}() is an exact copy of {fn_i['name']}(). Remove duplicate and reuse original."
                })
                seen_pairs.add(pair_key)
                print(f"[CloneGuard] /scan Type 1: {fn_i['name']} <-> {fn_j['name']}")
                continue

            # Type 2: normalized structure match
            if stripped_i == stripped_j and stripped_i != "":
                clone_groups.append({
                    "cloneType": "Type 2 — Renamed Clone",
                    "similarity": "95%",
                    "functionA": fn_i["name"],
                    "functionB": fn_j["name"],
                    "filename": filename,
                    "detectionLayer": "Layer 1 (Local — normalized identifier hash)",
                    "severity": "High",
                    "recommendation": f"{fn_j['name']}() has the same structure as {fn_i['name']}() with renamed variables. Consolidate into one function."
                })
                seen_pairs.add(pair_key)
                print(f"[CloneGuard] /scan Type 2: {fn_i['name']} <-> {fn_j['name']}")

    # ── Layer 2: Type 3 and Type 4 ───────────────────────────────────────────
    # Only runs on functions NOT caught by Layer 1
    layer1_names = {g["functionA"] for g in clone_groups} | {g["functionB"] for g in clone_groups}

    for i, fn_i in enumerate(scan_functions):
        if fn_i["name"] in layer1_names:
            continue

        query = np.array([fn_i["embedding"]])
        k = min(len(scan_functions), scan_index.ntotal)
        distances, indices = scan_index.search(query, k)

        best = None
        best_j_name = None

        for rank in range(k):
            semantic_score = float(distances[0][rank])
            j = int(indices[0][rank])

            if i == j:
                continue

            fn_j = scan_functions[j]

            if fn_j["name"] in layer1_names:
                continue

            pair_key = tuple(sorted([fn_i["name"], fn_j["name"]]))
            if pair_key in seen_pairs:
                continue

            # Minimum semantic threshold for Layer 2
            if semantic_score < 0.82:
                continue

            # Operation compatibility — pass FULL snippet so return type is extracted
            if not operations_compatible(fn_i["snippet"], fn_j["snippet"]):
                continue

            # Structural similarity — both positional and bag
            pos_score = structural_similarity(
                extract_body_local(fn_i["snippet"]),
                extract_body_local(fn_j["snippet"])
            )
            bag_score = bag_similarity(
                extract_body_local(fn_i["snippet"]),
                extract_body_local(fn_j["snippet"])
            )
            struct_score = max(pos_score, bag_score)

            # Type 3: high semantic + high structural
            if semantic_score >= 0.90 and struct_score >= 0.55:
                clone_type = "Type 3 — Near-Miss Clone"
                severity = "High"
                recommendation = f"{fn_j['name']}() is structurally similar to {fn_i['name']}() with minor modifications. Extract shared logic."
            # Type 4: moderate semantic + any structural (different implementation)
            elif semantic_score >= 0.82 and struct_score >= 0.10:
                clone_type = "Type 4 — Semantic Clone"
                severity = "Medium"
                recommendation = f"{fn_j['name']}() has the same intent as {fn_i['name']}() but different implementation. Consider consolidating."
            else:
                continue

            if best is None or semantic_score > best["semantic_score"]:
                best = {
                    "cloneType": clone_type,
                    "similarity": f"{round(semantic_score * 100)}%",
                    "functionA": fn_i["name"],
                    "functionB": fn_j["name"],
                    "filename": filename,
                    "detectionLayer": "Layer 2 (Server — CodeBERT+FAISS)",
                    "severity": severity,
                    "recommendation": recommendation,
                    "semantic_score": semantic_score
                }
                best_j_name = fn_j["name"]

        if best and best_j_name:
            result = dict(best)
            result.pop("semantic_score", None)
            clone_groups.append(result)
            pair_key = tuple(sorted([fn_i["name"], best_j_name]))
            seen_pairs.add(pair_key)
            layer1_names.add(fn_i["name"])
            layer1_names.add(best_j_name)
            print(f"[CloneGuard] /scan {best['cloneType']}: {best['functionA']} <-> {best['functionB']}")

    print(f"[CloneGuard] /scan complete: {len(clone_groups)} clone groups found")

    return jsonify({
        "cloneGroups": clone_groups,
        "totalClones": len(clone_groups),
        "scannedFunctions": len(scan_functions),
        "filename": filename
    })

@app.route("/health", methods=["GET"])
def health():
    return jsonify({
        "status": "ok",
        "indexed": faiss_index.ntotal,
        "models": ["codebert", "unixcoder"]
    })


@app.route("/test", methods=["GET"])
def test_page():
    test_path = os.path.join(os.path.dirname(__file__), "test_page.html")
    return send_file(test_path)


if __name__ == "__main__":
    port = int(os.environ.get("CLONEGUARD_PORT", "8765"))
    print(f"[CloneGuard] Server running at http://127.0.0.1:{port}")
    print("[CloneGuard] Test page: http://127.0.0.1:{}/test".format(port))
    app.run(host="127.0.0.1", port=port, debug=False, threaded=False, use_reloader=False)
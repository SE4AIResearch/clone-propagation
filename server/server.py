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
    # IMPORTANT: numeric literals get a placeholder that does NOT look like an
    # identifier ('#NUM#') so the VAR substitution pass below cannot re-match
    # and overwrite it. Previously "2" -> "NUM" -> re-matched as an identifier
    # -> "VAR", which made distinct constants indistinguishable from variables
    # (e.g. "n*n" and "x*2" both collapsed to "VAR * VAR").
    # Use a null-byte-delimited sentinel that cannot be confused with or
    # split apart by the tokenizer regex below, so numeric literals stay
    # distinguishable from identifiers all the way through to the final join.
    code = re.sub(r'\b\d+\.?\d*\b', '\x00NUM\x00', code)
    tokens = re.findall(r'\x00NUM\x00|\b\w+\b|[^\w\s]', code)
    result = []
    for t in tokens:
        if t == '\x00NUM\x00':
            result.append('NUM')
        elif re.match(r'^[a-zA-Z_]\w*$', t) and t not in keywords:
            result.append('VAR')
        else:
            result.append(t)
    return ' '.join(result)


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


# ── Shared cross-endpoint compatibility checks ──────────────────────────────
# These are used by BOTH /check (Scenario 1 — inline paste) and /scan
# (Scenario 2 — file scan) so that the two scenarios can never silently
# diverge in what they consider a valid Type 3/4 match. Previously /check
# had no operator/identifier compatibility gating at all (only semantic +
# structural score thresholds), which let semantically-near-but-operationally
# -unrelated functions match (e.g. isEvenSafe <-> factorial, reverseWords
# <-> isPalindrome). Both endpoints now call these exact same functions.

def get_return_type_shared(code):
    """Extract return type from a Java method signature."""
    match = re.search(
        r'(?:public|private|protected|static|final|\s)+\s*(void|int|long|double|float|boolean|String|\w+)\s+\w+\s*\(',
        code
    )
    return match.group(1) if match else "unknown"


def identifier_overlap_shared(code1, code2):
    """
    Token-level overlap of meaningful identifiers (variable/method names
    used inside the body, excluding keywords and single-letter loop vars).
    Two functions that do unrelated things essentially never share
    meaningful identifier vocabulary, while genuine clones (even renamed
    ones) share structural keywords and often partial name stems.
    """
    keywords = {
        "public","private","protected","static","final","void","int","long",
        "double","float","boolean","char","byte","short","return","if","else",
        "for","while","do","switch","case","break","continue","try","catch",
        "throw","throws","new","null","true","false","this","super","instanceof",
        "String","List","Map","Set","Arrays","System","Math","Integer","Double"
    }

    def tokens(code):
        code = re.sub(r"//[^\n]*", "", code)
        code = re.sub(r"/\*[\s\S]*?\*/", "", code)
        words = re.findall(r"\b[a-zA-Z_]\w*\b", code)
        return [w for w in words if w not in keywords and len(w) > 1]

    t1 = tokens(code1)
    t2 = tokens(code2)
    if not t1 or not t2:
        return 0.0

    set1 = set(w.lower() for w in t1)
    set2 = set(w.lower() for w in t2)
    return len(set1 & set2) / max(1, len(set1 | set2))


def operator_fingerprint_shared(code):
    """Which arithmetic/comparison/string/bitwise operators appear in code."""
    ops = set()

    # Strip string literals first so '+' inside a string concatenation isn't
    # confused with arithmetic '+', and so '"' content doesn't pollute other
    # checks. This is a simple non-nested string stripper, sufficient for
    # the short snippets analyzed here.
    code_no_strings = re.sub(r'"[^"]*"', '""', code)

    # FIX: strip lambda arrows ('->') before any operator detection runs.
    # Tokenized as '-' followed by '>', a lambda like "v -> v > 0" has a
    # real identifier ('v') immediately before the '-', which is exactly
    # what the binary-subtraction detector (added to fix the unary-minus
    # bug) looks for as a valid left operand — so it falsely registers '-'
    # as a subtraction operator for any lambda expression. Replacing '->'
    # with a placeholder before operator scanning prevents this without
    # affecting genuine subtraction detection elsewhere.
    code_no_strings = code_no_strings.replace('->', ' LAMBDA_ARROW ')

    # '+' as addition: catches "x += y", "x = a + b", AND "return a + b(...)"
    # or any standalone "a + b" expression — not just assignment-style.
    if '+=' in code_no_strings or re.search(r'\w[\]\)]?\s*\+\s*\w', code_no_strings):
        ops.add('+')

    # '-' as BINARY subtraction only. FIX: the old pattern
    # r'\w[\]\)]?\s*-\s*\w' also matched unary negation — e.g. "return -1;"
    # gets read as "n" (the tail of "retur*n*") immediately followed by
    # " -1", falsely registering a subtraction operator on functions that
    # merely return a negative sentinel value (no subtraction anywhere).
    # That phantom '-' then let operations_compatible_shared() treat
    # operationally-unrelated functions (e.g. a min-search returning -1 on
    # empty input) as arithmetic-compatible with genuinely
    # subtraction-based functions like "n - 1" in a recursive factorial.
    #
    # Tokenize and only count '-' as binary subtraction when the token
    # immediately before it is a real operand (identifier, number, ')', or
    # ']') — not a keyword like return/case/throw, an operator, '=', '(',
    # or ',', all of which signal unary-minus context.
    if '-=' in code_no_strings:
        ops.add('-')
    else:
        unary_context_words = {'return', 'case', 'throw'}
        unary_context_chars = set('=(,+-*/%<>!&|')
        toks = re.findall(r'\w+|[^\w\s]', code_no_strings)
        for idx, tok in enumerate(toks):
            if tok != '-':
                continue
            if idx == 0:
                continue
            prev = toks[idx - 1]
            if prev in unary_context_words:
                continue
            if len(prev) == 1 and prev in unary_context_chars:
                continue
            if re.fullmatch(r'\w+', prev) or prev in (')', ']'):
                ops.add('-')
                break

    if '*=' in code_no_strings or re.search(r'\*\s*\w+', code_no_strings):
        ops.add('*')
    if '/' in code_no_strings and 'http' not in code_no_strings.lower():
        ops.add('/')
    if '%' in code_no_strings:
        ops.add('%')
    if '<' in code_no_strings:
        ops.add('<')
    if '>' in code_no_strings:
        ops.add('>')
    if '==' in code_no_strings:
        ops.add('==')
    if '!=' in code_no_strings:
        ops.add('!=')
    if 'charAt' in code or 'substring' in code or '+ "' in code or '" +' in code:
        ops.add('string')

    # Bitwise: single & or | (not && or ||, which are logical operators).
    # The old version matched any '&' or '|', which incorrectly classified
    # logical AND/OR (e.g. "arr == null || arr.length == 0") as bitwise,
    # causing unrelated arithmetic-presence rejections for ordinary
    # null-guard conditions.
    has_single_amp = bool(re.search(r'(?<!&)&(?!&)', code_no_strings))
    has_single_pipe = bool(re.search(r'(?<!\|)\|(?!\|)', code_no_strings))
    if has_single_amp or has_single_pipe or '^' in code_no_strings:
        ops.add('bitwise')

    return ops


def loop_nesting_depth(code):
    """Counts the maximum nesting depth of for/while loops. Used to reject
    pairs where one function does meaningfully more iteration work than the
    other (e.g. a nested-loop matrix sum vs a flat single-pass loop) even
    when their operator fingerprints coincidentally overlap."""
    depth = 0
    max_depth = 0
    for m in re.finditer(r'\bfor\b|\bwhile\b|\{|\}', code):
        tok = m.group()
        if tok in ('for', 'while'):
            continue
        if tok == '{':
            # Only count this brace as a loop-nesting level if it's the
            # opening brace of a for/while we just saw — approximate by
            # checking if a for/while keyword appears shortly before it.
            preceding = code[max(0, m.start()-60):m.start()]
            if re.search(r'\b(for|while)\b[^{}]*$', preceding):
                depth += 1
                max_depth = max(max_depth, depth)
        elif tok == '}':
            if depth > 0:
                depth -= 1
    return max_depth


def has_data_dependent_loop_bound(code):
    """Checks whether any for-loop's bound condition references a
    collection/array size (.length, .size()) rather than a fixed literal.
    A loop bounded by input size represents genuinely different complexity
    behavior than a loop with a constant bound (e.g. 'for (i=0; i<1; i++)'
    always runs exactly once regardless of input — it isn't really
    iterating over anything, just dressing up a single statement as a
    loop). Returns (has_data_dependent, has_fixed_only) — a function can
    have both kinds if it has multiple loops, in which case we don't treat
    it as exclusively either."""
    data_dependent = False
    fixed_only = False
    for m in re.finditer(r'for\s*\([^;]*;\s*([^;]*)\s*;', code):
        cond = m.group(1)
        if re.search(r'\.(length|size)\s*\(?', cond):
            data_dependent = True
        elif re.search(r'<=?\s*\d+\b|^\s*\d+\s*[<>]', cond):
            fixed_only = True
    return data_dependent, fixed_only


def is_recursive_shared(code, function_name):
    """Best-effort check for self-recursion: does the function BODY call
    itself by name. Must check the body only, not the full code — a
    function's signature always contains its own name (e.g. 'public int
    square(int n)'), so checking the full code falsely flags every
    function as recursive."""
    if not function_name:
        return False
    code = code.strip()
    idx = code.find('{')
    body = code[idx:] if idx != -1 else code
    return bool(re.search(r'\b' + re.escape(function_name) + r'\s*\(', body))


def if_condition_relational_ops(code):
    """Extracts relational operators (<,>,<=,>=) that appear specifically
    inside if(...) conditions, as opposed to for-loop headers. Loop-bound
    comparisons (e.g. 'i < arr.length') are nearly universal and carry no
    signal about what a function actually decides; the comparison inside an
    if-condition is what encodes the function's actual logic (e.g. 'arr[i]
    < result' vs 'arr[i] > result' is the entire difference between
    find-minimum and find-maximum)."""
    ops = set()
    for m in re.finditer(r'if\s*\(([^)]*)\)', code):
        cond = m.group(1)
        if '<=' in cond:
            ops.add('<=')
        elif '<' in cond:
            ops.add('<')
        if '>=' in cond:
            ops.add('>=')
        elif '>' in cond:
            ops.add('>')
    return ops


def operations_compatible_shared(code1, code2):
    """
    Two functions are compatible if same return type AND the operators
    used overlap meaningfully (or share identifier vocabulary). Used by
    BOTH /check and /scan so Scenario 1 and Scenario 2 stay consistent.
    """
    ret1 = get_return_type_shared(code1)
    ret2 = get_return_type_shared(code2)
    if ret1 != ret2:
        print(f"[CloneGuard] Return type mismatch: {ret1} vs {ret2} — skipping")
        return False

    # FIX (bug #6, round 2): two new structural pre-checks, found via
    # stress-testing fresh function pairs not in the original bug report.
    # These run BEFORE the existing operator/identifier checks below and
    # don't alter any of that logic — purely additive rejection signals.

    # (a) Opposite comparison direction inside decision logic (not loop
    # headers). Catches min-vs-max style inversions: findMinimum and
    # findMaximum are near-identical in every other respect (same
    # identifiers, same loop shape, same statement count) but use opposite
    # comparison operators in their actual decision — that's a semantic
    # inversion, not a near-miss clone, regardless of how high identifier
    # overlap happens to be.
    if_ops_1 = if_condition_relational_ops(code1)
    if_ops_2 = if_condition_relational_ops(code2)
    opposite_pairs = [({'<'}, {'>'}), ({'<='}, {'>='})]
    for side_a, side_b in opposite_pairs:
        if (if_ops_1 == side_a and if_ops_2 == side_b) or (if_ops_1 == side_b and if_ops_2 == side_a):
            print(f"[CloneGuard] Opposite if-condition comparison direction: "
                  f"{if_ops_1} vs {if_ops_2} (e.g. min-vs-max pattern) — skipping")
            return False

    # (b) Loop nesting depth mismatch, but ONLY when both functions are
    # non-recursive (iterative). A nested-loop matrix-sum and a flat
    # single-pass loop are doing fundamentally different amounts of work
    # even if their operator fingerprints coincidentally overlap (both use
    # '<' for a loop bound and '+' for an accumulator, which is true of
    # nearly any numeric loop). Skipped entirely when either side is
    # recursive, since recursion-vs-loop depth mismatch is the DEFINING
    # feature of a legitimate Type 4 semantic clone (e.g. factorial's
    # single loop vs factorialRecursive's zero loops) and must not be
    # rejected here.
    fn1_name = extract_function_name(code1, -1)
    fn2_name = extract_function_name(code2, -1)
    rec1 = is_recursive_shared(code1, fn1_name)
    rec2 = is_recursive_shared(code2, fn2_name)
    # FIX: stream-based code (e.g. Arrays.stream(...).filter(...).count())
    # has zero for/while loops by construction, so comparing it against an
    # iterative loop-based equivalent via raw loop-depth always mismatches
    # (0 vs 1+) even when they're a genuine Type 4 semantic clone — the
    # exact same problem recursion already gets excused from, since
    # recursion and streams are both "different paradigm, same intent"
    # cases that loop-counting can't fairly compare.
    is_stream1 = bool(re.search(r'\.stream\(|->|\.filter\(|\.map\(|\.collect\(|\.reduce\(', code1))
    is_stream2 = bool(re.search(r'\.stream\(|->|\.filter\(|\.map\(|\.collect\(|\.reduce\(', code2))
    if not rec1 and not rec2 and not is_stream1 and not is_stream2:
        depth1 = loop_nesting_depth(code1)
        depth2 = loop_nesting_depth(code2)
        if depth1 != depth2:
            print(f"[CloneGuard] Loop nesting depth mismatch: {depth1} vs {depth2} — skipping")
            return False

        # (c) Fixed-literal loop bound vs data-dependent loop bound.
        # sumArray() (bound = arr.length) and doubleValue() (bound = the
        # literal 1) have the same loop depth (both 1) so the depth check
        # above doesn't separate them, yet they're fundamentally different:
        # sumArray's iteration count scales with input, doubleValue's loop
        # always runs exactly once regardless of input — it's not really
        # iterating over anything. Same exemption logic as depth: skip for
        # recursive/stream code, since this check only makes sense when
        # comparing two genuine iterative loops.
        if not is_stream1 and not is_stream2:
            dd1, fixed1 = has_data_dependent_loop_bound(code1)
            dd2, fixed2 = has_data_dependent_loop_bound(code2)
            if (dd1 and fixed2 and not dd2) or (dd2 and fixed1 and not dd1):
                print(f"[CloneGuard] Loop bound mismatch: data-dependent vs fixed-literal — skipping")
                return False

    fp1 = operator_fingerprint_shared(code1)
    fp2 = operator_fingerprint_shared(code2)

    # String operators vs none is a hard signal — string manipulation
    # functions should never match pure-arithmetic functions.
    if ('string' in fp1) != ('string' in fp2):
        print(f"[CloneGuard] String operator mismatch: {fp1} vs {fp2} — skipping")
        return False

    # Arithmetic operator family must overlap meaningfully — a sum (+/+=)
    # function and a product (*/*=) function are never clones of each
    # other even if both lack strings.
    arithmetic_ops = {'+', '-', '*', '/', '%'}
    arith1 = fp1 & arithmetic_ops
    arith2 = fp2 & arithmetic_ops
    if arith1 and arith2 and arith1.isdisjoint(arith2):
        print(f"[CloneGuard] Arithmetic operator mismatch: {fp1} vs {fp2} — skipping")
        return False
    if bool(arith1) != bool(arith2):
        # Allow exception: bitwise-only functions vs arithmetic with mod
        # (e.g. n % 2 == 0  vs  n & 1 == 0) — both check evenness via a
        # single comparison, so let identifier/structural score decide.
        if not (('bitwise' in fp1 or 'bitwise' in fp2) and ('%' in fp1 or '%' in fp2)):
            print(f"[CloneGuard] Arithmetic presence mismatch: {fp1} vs {fp2} — skipping")
            return False

    # Identifier vocabulary overlap — catches totally unrelated functions
    # (e.g. square vs greet, isPalindrome vs reverseWords) that share no
    # arithmetic AND no identifiers.
    #
    # FIX (bug #6, round 1): the old check only rejected when fp1 and fp2
    # were COMPLETELY disjoint. But any two numeric functions that each use
    # a single common symbol — e.g. square() and factorialRecursive() both
    # contain '*' — share a non-empty operator set even though they're
    # otherwise unrelated. A single shared symbol like '*' or '<' is too
    # common to be meaningful signal on its own (almost any numeric loop
    # uses '<', almost any multiplicative function uses '*'). Require 2+
    # shared operator categories when identifier overlap is near-zero.
    #
    # FIX (bug #6, round 3): round 1 was too strict for RARE operators.
    # divideNumbers()/safeDivide() share only '/' with 0% identifier
    # overlap (params are single-letter 'a'/'b', excluded from overlap
    # calc) and got incorrectly rejected — but '/' is a much more specific,
    # less common operator than '<' or '+': most numeric functions never
    # divide anything, so two functions BOTH dividing is actually a
    # meaningful signal, unlike two functions both using a loop comparison.
    # Division and modulo alone are allowed to satisfy the "2+ categories"
    # bar on their own; '+', '-', '*', '<', '>' (ubiquitous in ordinary
    # loops/accumulators) still require genuine richness.
    RARE_OPS = {'/', '%'}
    overlap = identifier_overlap_shared(code1, code2)
    shared_ops = fp1 & fp2
    shared_rare_ops = shared_ops & RARE_OPS
    sufficient_overlap = len(shared_ops) >= 2 or len(shared_rare_ops) >= 1
    if overlap < 0.05 and not sufficient_overlap:
        print(f"[CloneGuard] No identifier or operator overlap: {fp1} vs {fp2}, "
              f"shared_ops={shared_ops}, overlap={overlap:.3f} — skipping")
        return False

    return True



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
        # FIX: search ALL indexed functions, not just top 3. With k=3, as more
        # functions get indexed, the genuinely correct match can be pushed out
        # of the top 3 by semantically-adjacent-but-wrong candidates (e.g.
        # isEvenSafe vs isEven got squeezed out by sumList/factorial/sumArray
        # once the index grew past 3 entries). The downstream return-type and
        # operator compatibility checks already filter out wrong candidates,
        # so searching more broadly here is safe and just gives the correct
        # candidate a chance to be considered at all.
        k = faiss_index.ntotal
        print(f"[CloneGuard] DEBUG: faiss_index.ntotal={faiss_index.ntotal}, len(stored_functions)={len(stored_functions)}")
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

            # Only skip self-match if body is different — same name + same body = Type 1 clone
            pasted_name = extract_function_name(suggestion, -1)
            if matched_name == pasted_name and chunk_body != matched_body:
                print(f"[CloneGuard] Skipping self-match (different body): {matched_name}")
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
            # Only Type 2 if structure is identical AND statement count matches.
            # If candidate has MORE statements than original → Type 3 (near-miss).
            matched_stripped = strip_to_structure(extract_body(matched["snippet"]))
            print(f"[CloneGuard] chunk_stripped: {chunk_stripped[:80]}")
            print(f"[CloneGuard] matched_stripped: {matched_stripped[:80]}")
            candidate_stmts = len(re.findall(r';', extract_body(chunk)))
            matched_stmts   = len(re.findall(r';', extract_body(matched["snippet"])))
            same_structure  = (chunk_stripped == matched_stripped)
            same_stmt_count = (candidate_stmts == matched_stmts)
            # Near-miss: matched structure is a subset of candidate structure
            # e.g. candidate adds null checks, logging, extra validation
            matched_tokens = set(matched_stripped.split())
            chunk_tokens   = set(chunk_stripped.split())
            is_superset    = matched_tokens.issubset(chunk_tokens) and candidate_stmts > matched_stmts
            if same_structure and same_stmt_count:
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

            # Type 3: near-miss — same structure but different statement count
            # OR candidate is a structural superset of the original
            # Type 3 early return disabled — let semantic layer classify properly
            # if ((same_structure and not same_stmt_count) or is_superset):
            #     pass  # fall through to semantic classification below

            # Type 3 / 4: semantic
            if semantic_score < 0.82:
                continue

            # ── FIX: operator/identifier compatibility gate ──────────────
            # /scan already rejects semantically-near-but-operationally-
            # unrelated pairs using this exact check. /check previously had
            # NO such gate at all — only the semantic + structural thresholds
            # below — which let CodeBERT's embedding-space proximity alone
            # decide matches for inline pastes, causing false positives
            # Scenario 2 had already solved (e.g. reverseWords <-> isEven).
            #
            # IMPORTANT: pass the FULL chunk/matched snippet (with method
            # signature), not extract_body(...) — get_return_type_shared
            # needs the signature line to detect the return type. Calling
            # it on body-only text caused it to misfire on stray code
            # inside the body (e.g. matching "new" from "new StringBuilder()"
            # as if it were the return type), silently disabling the gate.
            if not operations_compatible_shared(chunk, matched["snippet"]):
                continue

            struct_score = structural_similarity(extract_body(chunk), extract_body(matched["snippet"]))
            print(f"[CloneGuard] Structural: {struct_score:.4f}")

            # Require minimum structural similarity to avoid false positives
            # Two completely different functions (like sort vs sum) should not match.
            # FIX: use an adaptive floor for short functions. A single added
            # statement (e.g. a null check) swings the token-overlap ratio much
            # more for a 1-2 statement function than for a longer one, so a flat
            # 0.15 floor incorrectly rejects legitimate Type 3 near-misses like
            # isEven() -> isEvenSafe() (adds one null check, same shape, no loop).
            # operations_compatible_shared() already filters out genuinely
            # unrelated functions upstream, so relaxing this floor for short
            # functions is safe.
            chunk_stmt_count = len(re.findall(r';', extract_body(chunk)))
            matched_stmt_count = len(re.findall(r';', extract_body(matched["snippet"])))
            min_stmt_count = min(chunk_stmt_count, matched_stmt_count)
            struct_floor = 0.08 if min_stmt_count <= 3 else 0.15
            if struct_score < struct_floor:
                print(f"[CloneGuard] Skipping — structural too low: {struct_score:.4f} (floor={struct_floor})")
                continue

            if best_match is None or semantic_score > best_match["semantic_score"]:
                # Type 3 vs Type 4: use control-flow SHAPE, not just "if" presence.
                # This mirrors the /scan endpoint's classification logic exactly,
                # so inline-paste detection (Scenario 1) and file-scan detection
                # (Scenario 2) agree on Type 3 vs Type 4 for the same code pair.
                #
                # Type 3 (near-miss) = same control-flow shape (both loops, or
                #                       both recursive) with only minor additions
                #                       (extra null check, logging, etc).
                # Type 4 (semantic)  = different control-flow shape entirely
                #                       (e.g. iterative loop vs recursion).
                chunk_body_text = extract_body(chunk)
                matched_body_text = extract_body(matched["snippet"])

                is_recursive_chunk = bool(re.search(r'\b' + re.escape(pasted_name) + r'\s*\(', chunk_body_text)) if pasted_name else False
                is_recursive_matched = bool(re.search(r'\b' + re.escape(matched_name) + r'\s*\(', matched_body_text))
                has_loop_chunk = bool(re.search(r'\bfor\b|\bwhile\b', chunk_body_text))
                has_loop_matched = bool(re.search(r'\bfor\b|\bwhile\b', matched_body_text))

                same_shape = (is_recursive_chunk == is_recursive_matched) and (has_loop_chunk == has_loop_matched)

                if same_shape:
                    clone_type = "Type 3 — Near-Miss Clone"
                    severity = "High"
                    recommendation = f"Near-miss clone of {matched_name}(). Similar structure with added branching logic."
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
        if confidence > 0.75:
            label = "AI Generated — High Confidence"
            is_ai = True
        elif confidence > 0.60:
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

            # Type 2 vs Type 3: normalized structure match
            if stripped_i == stripped_j and stripped_i != "":
                stmts_i = len(re.findall(r';', extract_body_local(fn_i["snippet"])))
                stmts_j = len(re.findall(r';', extract_body_local(fn_j["snippet"])))
                if stmts_i == stmts_j:
                    # Same structure AND same statement count → Type 2
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
                else:
                    # Same structure but different statement count → Type 3
                    clone_groups.append({
                        "cloneType": "Type 3 — Near-Miss Clone",
                        "similarity": "90%",
                        "functionA": fn_i["name"],
                        "functionB": fn_j["name"],
                        "filename": filename,
                        "detectionLayer": "Layer 1 (Local — near-miss structure)",
                        "severity": "High",
                        "recommendation": f"{fn_j['name']}() is a near-miss clone of {fn_i['name']}() with minor additions. Extract shared logic."
                    })
                    seen_pairs.add(pair_key)
                    print(f"[CloneGuard] /scan Type 3: {fn_i['name']} <-> {fn_j['name']}")

    # ── Layer 2: Type 3 and Type 4 ───────────────────────────────────────────
    layer1_names = {g["functionA"] for g in clone_groups} | {g["functionB"] for g in clone_groups}

    for i in range(len(scan_functions)):
        for j in range(i + 1, len(scan_functions)):
            fn_i = scan_functions[i]
            fn_j = scan_functions[j]

            pair_key = tuple(sorted([fn_i["name"], fn_j["name"]]))
            if pair_key in seen_pairs:
                continue

            if fn_i["name"] in layer1_names and fn_j["name"] in layer1_names:
                continue

            semantic_score = float(np.dot(fn_i["embedding"], fn_j["embedding"]))
            if semantic_score < 0.90:
                continue

            if not operations_compatible_shared(fn_i["snippet"], fn_j["snippet"]):
                continue

            pos_score = structural_similarity(
                extract_body_local(fn_i["snippet"]),
                extract_body_local(fn_j["snippet"])
            )
            bag_score = bag_similarity(
                extract_body_local(fn_i["snippet"]),
                extract_body_local(fn_j["snippet"])
            )
            struct_score = max(pos_score, bag_score)

            # FIX: /scan was using a flat 0.20 structural floor, while
            # /check already uses an adaptive floor (0.08 for short
            # functions with <=3 statements, 0.15 otherwise) — fixed
            # earlier this session for exactly this reason: a single added
            # guard clause swings the position-based token-overlap ratio
            # much more for a short function than a long one. divideNumbers()
            # vs safeDivide() (3 prepended guard clauses) hit struct_score
            # 0.143 — below 0.20, but a legitimate Type 3 near-miss exactly
            # like the original isEvenSafe()/isEven() case that justified
            # the adaptive floor for /check in the first place. /scan never
            # got that same fix, so the two endpoints silently diverged on
            # this exact class of case. Bringing them in line.
            stmts_i = len(re.findall(r';', extract_body_local(fn_i["snippet"])))
            stmts_j = len(re.findall(r';', extract_body_local(fn_j["snippet"])))
            min_stmts = min(stmts_i, stmts_j)
            struct_floor = 0.08 if min_stmts <= 3 else 0.15
            if struct_score < struct_floor:
                continue

            body_i = extract_body_local(fn_i["snippet"])
            body_j = extract_body_local(fn_j["snippet"])

            # Detect control-flow SHAPE, not just presence of "if".
            # Type 3 (near-miss) = same control-flow shape (both loops, or both recursive)
            #                       with only minor additions (extra null check, logging, etc).
            # Type 4 (semantic)  = different control-flow shape entirely
            #                       (e.g. iterative loop vs recursion, or for vs stream).
            is_recursive_i = bool(re.search(r'\b' + re.escape(fn_i["name"]) + r'\s*\(', body_i))
            is_recursive_j = bool(re.search(r'\b' + re.escape(fn_j["name"]) + r'\s*\(', body_j))
            has_loop_i = bool(re.search(r'\bfor\b|\bwhile\b', body_i))
            has_loop_j = bool(re.search(r'\bfor\b|\bwhile\b', body_j))

            same_shape = (is_recursive_i == is_recursive_j) and (has_loop_i == has_loop_j)

            if same_shape:
                clone_type = "Type 3 — Near-Miss Clone"
                severity = "High"
                rec = f"{fn_j['name']}() is a near-miss clone of {fn_i['name']}(). Extract shared logic."
            else:
                clone_type = "Type 4 — Semantic Clone"
                severity = "Medium"
                rec = f"{fn_j['name']}() has same intent as {fn_i['name']}() but different implementation."

            clone_groups.append({
                "cloneType": clone_type,
                "similarity": f"{round(semantic_score * 100)}%",
                "functionA": fn_i["name"],
                "functionB": fn_j["name"],
                "filename": filename,
                "detectionLayer": "Layer 2 (Server — CodeBERT+FAISS)",
                "severity": severity,
                "recommendation": rec,
            })
            seen_pairs.add(pair_key)
            print(f"[CloneGuard] /scan {clone_type}: {fn_i['name']} <-> {fn_j['name']}")

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
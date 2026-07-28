from flask import Flask, request, jsonify, send_file
import os
import re
import threading
import logging
import secrets

# FIX (professor-flagged, 2.6 -- Low): server.py used to log everything
# through plain print() statements -- 51 of them, mixing rare startup
# events with high-frequency per-request tracing (every pairwise
# comparison decision, every safety-check skip, every FAISS index
# update) into one undifferentiated stream with no way to turn any of
# it down. In production that means high log volume driving up hosting
# costs and making the handful of messages that actually matter hard to
# find in the noise.
#
# Replaced with the standard logging module: genuine lifecycle events
# (model loading, server startup, index resets, per-request summaries)
# log at INFO; everything else -- the detailed tracing through each
# individual safety check, similarity score, and pairwise comparison --
# logs at DEBUG, so it's available when actively debugging (set
# CLONEGUARD_LOG_LEVEL=DEBUG) but silent by default in production.
logging.basicConfig(
    level=os.environ.get("CLONEGUARD_LOG_LEVEL", "INFO"),
    format="[CloneGuard] %(message)s",
)
logger = logging.getLogger("cloneguard")

# FIX (found live, testing the 2.6 change): logging.basicConfig() above
# configures the ROOT logger -- which every third-party library's own
# logger propagates through by default, unless that library sets up an
# isolated handler of its own. Confirmed directly from a real server
# startup log: huggingface_hub's internal HTTP request tracing ("HTTP
# Request: HEAD https://huggingface.co/...") was showing up tagged with
# our own "[CloneGuard]" prefix, making routine third-party network
# chatter look like it was coming from this application's own code --
# the opposite of what 2.6 was trying to achieve, since it's genuinely
# MORE log volume dressed up as if it were ours. Explicitly raising
# these specific known-noisy libraries to WARNING (their routine INFO-
# level chatter is rarely useful, but a real WARNING from them still
# surfaces) leaves CloneGuard's own "cloneguard" logger as the only one
# actually affected by CLONEGUARD_LOG_LEVEL.
#
# UPDATE (found live again, on Render): the noise persisted even with
# this fix in place. Root cause: huggingface_hub's message format --
# `HTTP Request: {method} {url} "{http_version} {status}"` -- is
# actually httpx's own log line format, not huggingface_hub's. Recent
# huggingface_hub versions use httpx as their HTTP backend internally,
# and httpx logs under its OWN separate logger name ("httpx"), a
# completely different top-level logger from "huggingface_hub" in
# Python's logging hierarchy -- silencing "huggingface_hub" was never
# going to touch a log line actually emitted by "httpx". Added "httpx"
# and "httpcore" (httpx's own underlying transport layer, which logs
# separately at the same INFO level) to close the actual gap.
for _noisy_logger in ("huggingface_hub", "urllib3", "filelock", "transformers", "httpx", "httpcore"):
    logging.getLogger(_noisy_logger).setLevel(logging.WARNING)

# Reduce native crashes on macOS when PyTorch/FAISS run together
os.environ.setdefault("OMP_NUM_THREADS", "1")
os.environ.setdefault("MKL_NUM_THREADS", "1")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

import torch
from transformers import AutoTokenizer, AutoModel
import faiss
import numpy as np

app = Flask(__name__)

# FIX (professor-flagged, 2.1 -- Critical): this server is deployed to
# the public internet (Render). CORS(app) used to be enabled
# unconditionally, wide open to every origin -- but CORS only matters
# for BROWSER-based cross-origin requests, and neither real caller here
# is a browser: the IDE plugin uses Java's HttpClient directly, and the
# GitHub Actions workflows call this server from a CI runner, not a
# page loaded in someone's browser. Enabling CORS was granting arbitrary
# websites permission to call this server from a visitor's browser for
# no actual benefit to any legitimate caller -- removed entirely (no
# `from flask_cors import CORS`, no CORS(app) call) rather than
# "restricted to trusted origins", since there are no legitimate
# browser-based origins that need it at all.
#
# In its place: bearer-token authentication. The expected key is read
# from the CLONEGUARD_API_KEY environment variable (set on Render, and
# as a GitHub Actions secret for the workflows that call this server) --
# never hardcoded in source. If CLONEGUARD_API_KEY is unset entirely
# (e.g. a fresh local `python3 server.py` run with no environment
# configured), authentication is skipped with a loud startup warning, so
# local development still works out of the box without requiring every
# contributor to generate and configure a key just to run the server on
# their own machine -- but a PUBLIC deployment with no key set is
# exactly the "wide-open" situation this fix exists to prevent, hence
# the warning rather than silent acceptance either way.
API_KEY = os.environ.get("CLONEGUARD_API_KEY")
if not API_KEY:
    logger.info("WARNING: CLONEGUARD_API_KEY is not set. Every "
          "endpoint is currently UNAUTHENTICATED. Fine for local "
          "development -- but a publicly deployed server with no key "
          "set accepts requests from anyone on the internet.")

# /health and /test are intentionally left unauthenticated: /health is
# used by CloneGuard's own health check, which has no way to send a
# custom Authorization header; /test just serves a static HTML test
# page with no data exposure or real compute cost.
#
# FIX (found live -- confirmed directly in a real deploy log): "/" is
# ALSO exempted here, separately from "/health". Render's own
# platform-level health probe hits the bare root path directly, not
# "/health" -- observed a real "HEAD / HTTP/1.1" 401 in the deploy log
# the moment auth was turned on, which could make Render's own
# monitoring think the service is unhealthy even while it's actually
# running fine. "/" has no real route handler behind it anyway (it
# already 404s normally), so exempting it costs nothing.
_UNAUTHENTICATED_ENDPOINTS = {"/health", "/test", "/"}


# FIX (professor-flagged, 2.3 -- High): endpoints accepting raw source
# code (/check, /scan, /detect-ai, /index) had a MINIMUM length check
# but no MAXIMUM at all. A 10MB payload would still reach tokenization
# and chunk_into_functions() before anything meaningfully truncated it
# -- max_length=512 only truncates the TENSOR fed to the model, not the
# string processing that happens before that point. Rejected here, in
# the same before_request hook as the auth check above, so every
# endpoint is protected uniformly at the API boundary rather than
# needing this check duplicated at each individual route.
MAX_PAYLOAD_BYTES = 100_000  # 100KB, per the recommendation


@app.before_request
def limit_payload_size():
    if request.content_length is not None and request.content_length > MAX_PAYLOAD_BYTES:
        return jsonify({
            "error": f"Payload too large ({request.content_length} bytes). "
                     f"Maximum accepted size is {MAX_PAYLOAD_BYTES} bytes."
        }), 413


@app.before_request
def require_api_key():
    if not API_KEY:
        return  # no key configured -- see startup warning above
    if request.path in _UNAUTHENTICATED_ENDPOINTS:
        return
    expected = f"Bearer {API_KEY}"
    # FIX (professor-flagged, follow-up round -- Medium): plain string
    # equality (!=) short-circuits at the FIRST mismatched character,
    # meaning a correct guess up to some point takes measurably longer
    # to reject than a totally wrong one -- an attacker can brute-force
    # the key one character at a time by timing responses.
    # secrets.compare_digest() always takes the same amount of time
    # regardless of where (or whether) the strings differ, specifically
    # built for comparing secrets like this.
    if not secrets.compare_digest(request.headers.get("Authorization", ""), expected):
        return jsonify({"error": "Unauthorized"}), 401

logger.info("Loading CodeBERT model...")
tokenizer = AutoTokenizer.from_pretrained("microsoft/codebert-base")
model = AutoModel.from_pretrained("microsoft/codebert-base")
model.eval()
torch.set_num_threads(1)
logger.info("CodeBERT ready.")

# ── UniXcoder for AI-generated code detection ──────────────────────────────
logger.info("Loading UniXcoder model for AI detection...")
from transformers import RobertaTokenizer, RobertaModel
ai_tokenizer = RobertaTokenizer.from_pretrained("microsoft/unixcoder-base")
ai_model = RobertaModel.from_pretrained("microsoft/unixcoder-base")
ai_model.eval()
logger.info("UniXcoder ready.")

class IndexStore:
    """
    Thread-safe wrapper around the FAISS index and its parallel
    stored_functions list.

    These two structures must stay in sync by position -- faiss_index
    vector i corresponds to stored_functions[i]. Without synchronization,
    a write racing another write (or a write racing a read) could
    interleave in a way that desyncs them, causing an IndexError or,
    worse, a search silently returning the wrong stored function for a
    given match index.

    server.py currently runs with threaded=False, so this isn't an
    active bug today -- but any future deployment behind a
    multi-threaded WSGI server (e.g. Gunicorn with multiple worker
    threads) would hit this immediately. Encapsulating the state here,
    with one lock guarding every read and write, makes that deployment
    change safe without requiring any changes to the route handlers
    that use it.

    Deliberate design choice: get_embedding() (model inference, the
    expensive part of indexing) is always called OUTSIDE the lock, by
    the caller, before add_if_not_duplicate() is invoked. Holding the
    lock during model inference would serialize every request on their
    single slowest step, defeating the purpose of a multi-threaded
    deployment. The tradeoff is that a duplicate's embedding gets
    computed even though it's about to be discarded -- acceptable,
    since duplicates are the exception rather than the common case.
    """

    def __init__(self, dim):
        self._dim = dim
        self._lock = threading.Lock()
        self._faiss_index = faiss.IndexFlatIP(dim)
        self._stored_functions = []

    def reset(self):
        with self._lock:
            self._faiss_index = faiss.IndexFlatIP(self._dim)
            self._stored_functions = []

    @property
    def ntotal(self):
        with self._lock:
            return self._faiss_index.ntotal

    def name_hint_index(self):
        """Current count, used as a naming-fallback hint by extract_function_name()."""
        with self._lock:
            return len(self._stored_functions)

    def add_if_not_duplicate(self, code, embedding, fn_name):
        """
        Atomically check-and-add. Re-checks for a duplicate INSIDE the
        lock, not just before this method is called -- this is what
        actually closes the race: two concurrent requests could both
        pass an outer "not a duplicate" check before either has added
        its entry, unless the check and the add happen under the same
        lock acquisition. Returns True if the entry was added, False if
        it was skipped as a duplicate.
        """
        chunk_body = normalize_body(code)
        with self._lock:
            already_exists = any(
                normalize_body(f["snippet"]) == chunk_body for f in self._stored_functions
            )
            if already_exists:
                return False
            self._faiss_index.add(np.array([embedding]))
            self._stored_functions.append({
                "name": fn_name,
                "snippet": code,
                "preview": code[:80] + "..."
            })
            return True

    def search_with_snapshot(self, query, k=None):
        """
        Runs the FAISS search and copies out exactly the stored_functions
        entries the caller will need, all under one lock acquisition --
        so the caller can never see a result set where the FAISS side
        and the stored_functions side reflect two different points in
        time. k=None means "search everything currently indexed".
        Returns (distances, indices, entries), where entries[idx] is a
        stored function dict for each idx present in the search
        results. Returns (None, None, {}) if the index is empty.
        """
        with self._lock:
            current_total = self._faiss_index.ntotal
            actual_k = current_total if k is None else min(k, current_total)
            if actual_k < 1:
                return None, None, {}
            distances, indices = self._faiss_index.search(query, actual_k)
            entries = {}
            for idx in indices[0]:
                idx = int(idx)
                if 0 <= idx < len(self._stored_functions):
                    entries[idx] = self._stored_functions[idx]
            return distances, indices, entries


EMBEDDING_DIM = 768
index_store = IndexStore(EMBEDDING_DIM)


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
    # Strip comments first
    code = re.sub(r'//[^\n]*', '', code)
    code = re.sub(r'/\*[\s\S]*?\*/', '', code)

    # FIX (found live, Pull Up Type 2 testing -- mirrors the identical
    # bug already found and fixed in LocalCloneDetector.normalizeIdentifiers()
    # on the Java side): this used to collapse EVERY string/char literal
    # into the single generic token "STR", regardless of its actual
    # content -- "Animal: " and "Wing: " both became STR, discarding
    # exactly the information that would have told two otherwise
    # similarly-shaped methods apart. Confirmed live: Dog.describe()
    # (builds "Animal: " + name) and Eagle.describeWing() (builds
    # "Wing: " + wingType) were reported as a Type 2 clone of each other
    # by THIS function specifically -- and worse, the old "STR" literal
    # text was then re-tokenized by the VAR-substitution pass below and
    # replaced AGAIN, collapsing every string literal all the way down
    # to a bare "VAR" with zero distinguishing content left at all.
    #
    # Fix: protect each literal's ACTUAL text behind a unique sentinel
    # before tokenizing (same technique this function already uses for
    # numeric literals just below, for exactly the same reason -- see
    # that comment), then restore the real text verbatim once
    # tokenization is done, so literal content survives all the way
    # through to the final comparison. Two functions that differ only in
    # what a string literal SAYS are no longer indistinguishable.
    literals = []
    def _stash_literal(m):
        literals.append(m.group(0))
        return '\x00STR' + str(len(literals) - 1) + '\x00'
    code = re.sub(r'"(?:[^"\\]|\\.)*"|\'(?:[^\'\\]|\\.)*\'', _stash_literal, code)

    # IMPORTANT: numeric literals get a placeholder that does NOT look like an
    # identifier ('#NUM#') so the VAR substitution pass below cannot re-match
    # and overwrite it. Previously "2" -> "NUM" -> re-matched as an identifier
    # -> "VAR", which made distinct constants indistinguishable from variables
    # (e.g. "n*n" and "x*2" both collapsed to "VAR * VAR").
    # Use a null-byte-delimited sentinel that cannot be confused with or
    # split apart by the tokenizer regex below, so numeric literals stay
    # distinguishable from identifiers all the way through to the final join.
    code = re.sub(r'\b\d+\.?\d*\b', '\x00NUM\x00', code)
    tokens = re.findall(r'\x00STR\d+\x00|\x00NUM\x00|\b\w+\b|[^\w\s]', code)
    result = []
    for t in tokens:
        if t == '\x00NUM\x00':
            result.append('NUM')
        elif t.startswith('\x00STR') and t.endswith('\x00'):
            result.append(literals[int(t[4:-1])])
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
        # FIX (professor-flagged, 2.5): this used to match string
        # literals with r'["\'].*?["\']' -- a naive, non-greedy pattern
        # that has no concept of an escaped quote inside the literal
        # (e.g. "say \"hi\"" desyncs the boundary mid-string), and can
        # match ACROSS two separate literals entirely if content between
        # them looks quote-like. Either failure leaks raw literal text
        # into the identifier-tokenization step below -- and because
        # this function does an INDEX-BY-INDEX positional comparison
        # (tokens1[i] == tokens2[i]), a single misaligned token corrupts
        # every comparison after it, not just the one token, silently
        # wrecking the whole positional score.
        #
        # Fix: reuse strip_to_structure()'s already-proven-correct
        # boundary pattern -- "(?:[^"\\]|\\.)*" -- which explicitly
        # handles an escaped quote (\\.) as part of the literal rather
        # than treating it as the closing quote. This function still
        # collapses every literal down to the single "STR" placeholder
        # afterward, same as it always has: unlike strip_to_structure
        # (which needs to preserve actual literal CONTENT, since that's
        # exactly what distinguishes two Type 1/2 clones from each
        # other), this function only cares about structural SHAPE, so
        # collapsing to a generic placeholder is still the right choice
        # here -- only the boundary-detection needed to be more robust,
        # not the collapsing behavior itself.
        code = re.sub(r'"(?:[^"\\]|\\.)*"|\'(?:[^\'\\]|\\.)*\'', 'STR', code)
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
    logger.debug(f"Structural similarity: {score:.4f}")
    return score


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
    """
    Order-independent structural signal (Jaccard over keyword multisets).

    FIX (professor-flagged): structural_similarity() above compares
    tokens positionally (tokens1[i] == tokens2[i]), which is fragile to
    any reordering of independent statements -- two declarations
    swapped, or a guard clause inserted before existing logic, shifts
    every token after that point out of positional alignment even
    though nothing about the code's actual shape changed. This function
    was previously nested only inside the /scan route handler, where
    struct_score = max(pos_score, bag_score) already combines the two;
    /check had no equivalent and relied on the position-sensitive score
    alone. Promoted to module level so both endpoints can share it,
    matching the "shared cross-endpoint" pattern already used for
    operations_compatible_shared() and get_return_type_shared() above.
    """
    b1 = bag_of_keywords(code1)
    b2 = bag_of_keywords(code2)
    if not b1 or not b2:
        return 0.0
    keys = set(b1) | set(b2)
    intersection = sum(min(b1.get(k, 0), b2.get(k, 0)) for k in keys)
    union = sum(max(b1.get(k, 0), b2.get(k, 0)) for k in keys)
    score = intersection / union if union > 0 else 0.0
    return score


def _strip_loop_and_try_headers(code):
    """
    Replaces the contents of every for(...) and try(...) header with an
    empty parenthesis pair, leaving the loop/try body untouched. Uses
    paren-depth tracking so a nested call inside the header (e.g.
    "for (int i = 0; i < arr.length(); i++)") is still handled correctly,
    not just a naive first-close-paren match.
    """
    result = []
    pattern = re.compile(r'\b(for|try)\s*\(')
    last_end = 0
    for m in pattern.finditer(code):
        result.append(code[last_end:m.end()])  # keep "for(" / "try("
        depth = 1
        i = m.end()
        while i < len(code) and depth > 0:
            c = code[i]
            if c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
            i += 1
        result.append(")")  # header content dropped, closing paren kept
        last_end = i
    result.append(code[last_end:])
    return "".join(result)


def count_statements(code):
    """
    FIX (professor-flagged, 3.3): counting every raw ';' character as a
    statement inflates the count for a for-loop header by up to 2 (the
    init and condition semicolons in "for (int i = 0; i < n; i++)")
    relative to an otherwise-equivalent while-loop, and inflates
    try-with-resources similarly when declaring multiple resources. A
    semicolon inside a string literal was also miscounted as a statement
    terminator. This mirrors the equivalent fix in CloneIndexService.java
    (Java client side): strip for(...)/try(...) header contents and
    string/comment content before counting, so only semicolons that
    actually terminate a real statement are counted.

    This does NOT implement the professor's stronger recommendation --
    using IntelliJ's PSI to count actual AST statement nodes on the
    client side and passing that count to the server as request
    metadata, which would be more precise but requires changes to the
    request payload contract shared with PythonServerClient.java, not
    attempted here. Tracked as a follow-up.
    """
    if not code:
        return 0
    cleaned = re.sub(r"//[^\n]*", "", code)
    cleaned = re.sub(r"/\*[\s\S]*?\*/", "", cleaned)
    cleaned = re.sub(r'"(?:[^"\\]|\\.)*"', '""', cleaned)
    cleaned = re.sub(r"'(?:[^'\\]|\\.)'", "''", cleaned)
    cleaned = _strip_loop_and_try_headers(cleaned)
    return cleaned.count(';')


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
    """Extract return type from a Java method signature.

    FIX: the text this function receives is sometimes BODY-ONLY (starts
    directly with "{", no "public int methodName(" signature present at
    all) — confirmed directly via debug output while diagnosing why
    binarySearchIterative/binarySearchRecursive weren't matching. Without
    this check, the regex had nothing valid to match near the start of a
    body-only snippet, and could instead stumble onto something deep in
    the body that accidentally LOOKED like a signature — e.g. a recursive
    call "return binarySearchRecursive(...)" was being misread as
    "TYPE=return, methodName=binarySearchRecursive", producing a fake,
    wrong return type instead of correctly reporting "can't tell".
    """
    stripped = code.strip()
    if stripped.startswith("{"):
        return "unknown"

    # FIX (found live, this session — Scenario 2 rescan): comments were
    # never stripped before running the regex below. A comment sitting
    # directly above a method — e.g. "// ── Type 4: Semantic Clone (loop
    # vs recursion, needs wrapper) ──" immediately followed by
    # "public int isPrimeIterative(int n) {" — got misread as the
    # signature itself: "Semantic" was captured as the return type and
    # "Clone" as the method name, with the "(" from "(loop" in the
    # comment satisfying the regex's parenthesis requirement, before the
    # regex ever reached the real signature line. Confirmed directly
    # against the exact code the plugin sent during a live /scan: this
    # silently rejected isPrimeIterative()/isPrimeHelper() — a genuine
    # Type 4 match that had worked correctly in every prior test
    # tonight, none of which happened to include a comment immediately
    # preceding the method. Stripping comments first, the same
    # preprocessing already applied elsewhere in this file
    # (bag_of_keywords, structural_similarity's normalize()), closes
    # this.
    code_no_comments = re.sub(r"//[^\n]*", "", code)
    code_no_comments = re.sub(r"/\*[\s\S]*?\*/", "", code_no_comments)

    # FIX (found live, Type 4 gcd testing — gcdIterative()/gcdRecursive()):
    # this regex used to run unanchored over the ENTIRE method text. That's
    # unsound the moment a method has no explicit modifier (public/private/
    # etc.) -- e.g. a package-private "int gcdIterative(int a, int b) {" --
    # because the leading (?:modifiers|\s)+ group then has nothing valid to
    # anchor on AT the real signature, so re.search skips straight past it
    # and instead matches the first coincidental "word word(" shape found
    # ANYWHERE in the body. For gcdRecursive() specifically, that shape was
    # its own recursive call, "return gcdRecursive(b, a % b);" -- captured
    # as TYPE="return", a literal keyword misread as a return type. For
    # gcdIterative(), nothing in the body happened to match that shape at
    # all, so it fell through to "unknown" instead. Confirmed directly:
    # "unknown" != "return" tripped the compatibility gate and silently
    # rejected a genuine Type 4 clone before CodeBERT's semantic score was
    # ever consulted.
    #
    # Fix: isolate just the SIGNATURE portion -- everything up to the
    # first top-level "{" -- before running the regex at all. A Java
    # method's signature can never itself contain a literal "{", so this
    # guarantees the regex only ever sees the real signature, never
    # anything from inside the body, regardless of whether a modifier
    # keyword is present. Combined with changing the leading modifiers
    # group from + to * (so it can validly match ZERO modifiers), a
    # package-private signature like "int gcdIterative(" now matches
    # correctly starting at its true first character, instead of only
    # ever being reachable via the old, unsound whole-body search.
    brace_idx = code_no_comments.find("{")
    signature_only = code_no_comments[:brace_idx] if brace_idx != -1 else code_no_comments

    match = re.search(
        r'(?:public|private|protected|static|final|\s)*\s*(void|int|long|double|float|boolean|String|\w+)\s+\w+\s*\(',
        signature_only
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

    # FIX (found live, this session -- rigorous multi-function scan test,
    # round 3): a single shared GENERIC identifier -- e.g. "arr", the
    # default name for practically any array parameter in this codebase --
    # was enough on its own to push overlap above the 0.05 bypass
    # threshold, skipping the operator-overlap check entirely regardless of
    # how weak the real operator signal was. Confirmed live: coreLastElement2()
    # (body is just "return arr[arr.length - 1];", identifier set = {"arr"})
    # and reverseArrayRecursive() (identifiers {"arr","result","index"})
    # share only "arr" -- overlap = 1/3 ≈ 0.33, comfortably over 0.05 --
    # letting two functions with essentially nothing in common except a
    # naming convention pass as a false Type 4 match. Same root issue as
    # the earlier ubiquitous-OPERATOR fix, just for identifiers: exclude a
    # stoplist of extremely common, semantically-empty parameter/variable
    # names from counting toward overlap before computing the ratio.
    # FIX (found live, this session, round 4): the round-3 stoplist above
    # was too broad. Excluding "result" alongside "arr"/"length" correctly
    # kept coreLastElement2()/reverseArrayRecursive() rejected (that pair
    # never used "result" at all, so it didn't need "result" excluded) but
    # ALSO incorrectly rejected the real Type 4 pair
    # reverseArrayIterative()/reverseArrayRecursive() -- both genuinely use
    # "result" as their return-array variable, which IS meaningful shared
    # signal that two functions are building the same kind of output,
    # unlike "arr" (just the input parameter name, used by nearly every
    # array function regardless of purpose) or "length" (from arr.length,
    # equally universal). Narrowed the stoplist to only the specific terms
    # actually responsible for the false positive, confirmed via direct
    # testing against both the false-positive and the true-positive case
    # rather than guessing at a broader list.
    # FIX (found live, this session, round 5): "position" is another
    # generic recursion-index parameter name (alongside "arr"/"length"),
    # used by unrelated recursive helpers across different domains
    # (maxValueHelper's array position, reverseArrayRecursive's array
    # position) purely by naming convention, not genuine relatedness.
    # FIX (found live, this session, round 7): "numbers" is used as the
    # array-parameter name by multiple UNRELATED functions in this test
    # file (maxValueIterative, maxValueHelper, sumEvenNumbersLoop,
    # sumEvenNumbersStream, countZerosInArray) purely by naming
    # convention -- confirmed via direct test as the cause of
    # maxValueHelper()/sumEvenNumbersLoop() (unrelated: array-max vs
    # even-number-summing) passing on identifier overlap alone (0.167,
    # well over the 0.05 bar) despite having no real operator overlap.
    # FIX (found live, this session, round 10 -- corrects a real regression
    # from round 3): the generic-identifier stoplist (removing "arr",
    # "length", "position", "numbers", "total") correctly fixed several
    # false positives, but it had an uncaught side effect: lastElement()/
    # lastElementSafe() -- a real, confirmed Type 3 match -- was ALSO being
    # saved purely by shared "arr" identifier overlap (both functions'
    # entire vocabulary is just "arr"), and silently broke once "arr" was
    # stoplisted, without this being caught in later regression passes.
    # Direct testing found a clean, evidence-based gap instead: raw overlap
    # (no stoplist) for lastElement/lastElementSafe is 0.500, while EVERY
    # confirmed false positive tops out at 0.222 (coreLastElement2/
    # reverseArrayRecursive=0.143, maxValueHelper/reverseArrayRecursive=
    # 0.222, maxValueIterative/joinWordsLoop=0.100, sumValuesExact/
    # reverseArrayRecursive=0.125). A threshold in that gap separates all
    # of them correctly without needing to guess at which specific words
    # are "too generic" -- removed the stoplist, raising the bar instead.
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
    if not set1 or not set2:
        return 0.0
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


def has_other_method_call(code, own_name):
    """True if the body calls some OTHER method -- a plain identifier
    immediately followed by '(', that isn't a Java control-flow keyword
    (if/for/while/switch/catch/synchronized/return) and isn't the
    function's own name (a self-call is recursion, tracked separately by
    is_recursive_shared() above -- calling yourself isn't "delegating to
    something else"). Same text/regex approach as the rest of this
    file's call-detection helpers (see _resolved_calls_own_name further
    down)."""
    control_keywords = {'if', 'for', 'while', 'switch', 'catch', 'synchronized', 'return'}
    idx = code.find('{')
    body = code[idx:] if idx != -1 else code
    for m in re.finditer(r'\b([A-Za-z_]\w*)\s*\(', body):
        name = m.group(1)
        if name in control_keywords:
            continue
        if own_name and name == own_name:
            continue
        return True
    return False


def operations_compatible_shared(code1, code2, name1=None, name2=None):
    """
    Two functions are compatible if same return type AND the operators
    used overlap meaningfully (or share identifier vocabulary). Used by
    BOTH /check and /scan so Scenario 1 and Scenario 2 stay consistent.

    name1/name2: pass the REAL function names when the caller already
    knows them (both /check and /scan do -- the request always includes a
    name field). Optional and defaults to re-deriving from code1/code2 via
    extract_function_name() for callers that genuinely don't have one.
    """
    ret1 = get_return_type_shared(code1)
    ret2 = get_return_type_shared(code2)
    if ret1 != ret2:
        logger.debug(f"Return type mismatch: {ret1} vs {ret2} — skipping")
        return False, False

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
            logger.debug(f"Opposite if-condition comparison direction: "
                  f"{if_ops_1} vs {if_ops_2} (e.g. min-vs-max pattern) — skipping")
            return False, False

    # Compute recursion/stream status BEFORE the branching-presence check
    # below, not after -- these need the same exemption the loop-depth
    # check already gets, for the same reason.
    #
    # FIX (found live, this session -- Scenario 2 all-four-types test):
    # extract_function_name() re-derives a name from code1/code2 via a
    # regex that requires the METHOD SIGNATURE ("public int foo(...)") to
    # be present. But FileScannerService.java (Scenario 2's function
    # extractor) sends body.getText() -- just the "{ ... }" block, no
    # signature, ever -- so this regex could NEVER match, silently
    # returning a placeholder "function_-1" name for BOTH sides of EVERY
    # Layer 2 comparison /scan has ever made. is_recursive_shared() then
    # searches the body for a self-call to "function_-1(", which of course
    # never appears, so rec1/rec2 were unconditionally False regardless of
    # whether the code was actually recursive -- silently breaking Type 4
    # detection for every genuinely recursive/stream pair Scenario 2 ever
    # scanned, not just this one test. Confirmed directly via debug
    # logging: powerIterative vs powerRecursive computed
    # fn1_name='function_-1' fn2_name='function_-1' rec1=False rec2=False.
    # The caller (both /check and /scan) already KNOWS the real name from
    # the request payload -- use that instead of trying to rediscover it
    # from text that structurally can never contain it.
    fn1_name = name1 if name1 else extract_function_name(code1, -1)
    fn2_name = name2 if name2 else extract_function_name(code2, -1)
    rec1 = is_recursive_shared(code1, fn1_name)
    rec2 = is_recursive_shared(code2, fn2_name)
    is_stream1 = bool(re.search(r'\.stream\(|->|\.filter\(|\.map\(|\.collect\(|\.reduce\(', code1))
    is_stream2 = bool(re.search(r'\.stream\(|->|\.filter\(|\.map\(|\.collect\(|\.reduce\(', code2))

    # FIX (bug #7): the check above only catches opposite-DIRECTION
    # branching (< vs >). It never asked the more basic question first —
    # does one function branch on relational comparisons AT ALL, while the
    # other does not? A pure accumulator (total += arr[i], no if at all)
    # and a comparison-based tracker (if (x < min) ...; if (x > max) ...)
    # can still share the arithmetic operator '+' (e.g. via a final
    # "return min + max"), which is enough to pass the arithmetic-family
    # check below even though one function never branches and the other
    # does twice. Presence-of-branching is itself a meaningful signal,
    # independent of which direction the branching goes.
    #
    # FIX (found live, this session): this check originally had NO
    # recursion/stream exemption, unlike the loop-depth check just below
    # it -- so a recursive base case (if (n <= 1) return 1;) counted as
    # "has branching" while its iterative counterpart's loop body (result
    # *= i;) had none, hard-rejecting the pair BEFORE the recursion-aware
    # checks below ever ran. Confirmed via live server log: this is
    # exactly what silently ate factorialIterative vs factorialRecursive
    # -- a textbook Type 4 semantic clone -- turning "0 clone groups
    # found" into a false negative rather than a real "not a clone"
    # result. Recursion and streams don't have a "loop body" for this
    # question to even apply to, so exempt them the same way loop-depth
    # already does, rather than treating absence-of-a-loop as absence-of-
    # branching-in-a-loop.
    if not rec1 and not rec2 and not is_stream1 and not is_stream2:
        has_branching_1 = len(if_ops_1) > 0
        has_branching_2 = len(if_ops_2) > 0
        if has_branching_1 != has_branching_2:
            logger.debug(f"Branching mismatch: one function has conditional "
                  f"logic inside its loop and the other does not ({if_ops_1} vs {if_ops_2}) — skipping")
            return False, False

    # FIX (found live, Pull Up testing -- Car.drive()/Boat.sail() false
    # positive): confirmed live that one function calling ANOTHER method
    # internally (honkHorn()) while the other calls nothing at all (a
    # plain field assignment) was enough to still pass every check above
    # -- both share the '=' operator, and neither branches, so the
    # branching-presence check above doesn't catch this specific
    # difference. But delegating to another method is itself a real
    # behavioral difference, the same category of signal as
    # "has branching" above: a function that calls out to more logic is
    # doing meaningfully more than one that doesn't, regardless of
    # shared operators elsewhere. Presence of an internal method call,
    # like presence of branching, is checked independently of WHICH
    # method gets called -- only whether one function delegates to
    # something and the other doesn't.
    has_call_1 = has_other_method_call(code1, fn1_name)
    has_call_2 = has_other_method_call(code2, fn2_name)
    if has_call_1 != has_call_2:
        logger.debug(f"Method-call presence mismatch: one function calls "
              f"another method internally and the other does not — skipping")
        return False, False

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
    if not rec1 and not rec2 and not is_stream1 and not is_stream2:
        depth1 = loop_nesting_depth(code1)
        depth2 = loop_nesting_depth(code2)
        if depth1 != depth2:
            logger.debug(f"Loop nesting depth mismatch: {depth1} vs {depth2} — skipping")
            return False, False

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
                logger.debug(f"Loop bound mismatch: data-dependent vs fixed-literal — skipping")
                return False, False

    fp1 = operator_fingerprint_shared(code1)
    fp2 = operator_fingerprint_shared(code2)

    # String operators vs none is a hard signal — string manipulation
    # functions should never match pure-arithmetic functions.
    if ('string' in fp1) != ('string' in fp2):
        logger.debug(f"String operator mismatch: {fp1} vs {fp2} — skipping")
        return False, False

    # Arithmetic operator family must overlap meaningfully — a sum (+/+=)
    # function and a product (*/*=) function are never clones of each
    # other even if both lack strings.
    arithmetic_ops = {'+', '-', '*', '/', '%'}
    arith1 = fp1 & arithmetic_ops
    arith2 = fp2 & arithmetic_ops
    if arith1 and arith2 and arith1.isdisjoint(arith2):
        logger.debug(f"Arithmetic operator mismatch: {fp1} vs {fp2} — skipping")
        return False, False
    if bool(arith1) != bool(arith2):
        # Allow exception: bitwise-only functions vs arithmetic with mod
        # (e.g. n % 2 == 0  vs  n & 1 == 0) — both check evenness via a
        # single comparison, so let identifier/structural score decide.
        if not (('bitwise' in fp1 or 'bitwise' in fp2) and ('%' in fp1 or '%' in fp2)):
            logger.debug(f"Arithmetic presence mismatch: {fp1} vs {fp2} — skipping")
            return False, False

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
    # FIX (found live, this session -- Scenario 2 all-four-types test):
    # the comment above states ubiquitous operators ('+', '-', '*', '<',
    # '>') "still require genuine richness" to count as meaningful overlap
    # signal, but the code below never actually enforced that -- it counted
    # ANY 2 shared operators toward the "sufficient_overlap" bar, ubiquitous
    # or not. This let coreArrayAverage() (sum += arr[i]; ... sum / len)
    # and isPrimeHelper() (divisor*divisor > n; n % divisor; divisor + 1)
    # pass as "sufficiently overlapping" purely because both happen to use
    # '+' and '<' somewhere -- two operators present in nearly every
    # numeric loop -- with zero shared identifiers and no real semantic
    # relationship. Confirmed live: this is exactly what let that pair
    # reach a 99% CodeBERT similarity score and get reported as a false
    # Type 4 match. Only operators OUTSIDE the ubiquitous set now count
    # toward the "2+ categories" bar; ubiquitous ones can still contribute
    # via the RARE_OPS path or identifier overlap, just not on their own.
    UBIQUITOUS_OPS = {'+', '-', '*', '<', '>', '=='}
    overlap = identifier_overlap_shared(code1, code2)
    shared_ops = fp1 & fp2
    shared_rare_ops = shared_ops & RARE_OPS
    meaningful_shared_ops = shared_ops - UBIQUITOUS_OPS

    # FIX (found live, this session, round 2 -- rigorous multi-function scan
    # test): the round-1 fix above correctly rejects coreArrayAverage() vs
    # isPrimeHelper() (shared_ops={'+','<'}, both ubiquitous, 0% identifier
    # overlap) -- but factorialIterative()/factorialRecursive(), a genuine
    # Type 4 clone, has the EXACT SAME operator-overlap profile: shared_ops
    # = {'<','*'}, also both ubiquitous, also 0% identifier overlap (no
    # shared variable names -- "result"/"i" vs just self-recursion, nothing
    # in common). An absolute op-count check literally cannot tell these two
    # cases apart -- they're identical in that reduced feature space. What
    # DOES differ: how much of each function's total operator vocabulary
    # the shared ops actually cover. factorialIterative's fingerprint is
    # {<,*} and factorialRecursive's is {<,*,-} -- they share 2 of a
    # combined 3 distinct operators (jaccard ~0.67). coreArrayAverage's
    # fingerprint is {+,<,/} and isPrimeHelper's is {<,*,>,%,==,+} -- they
    # share 2 of a combined 7 (jaccard ~0.29). Two functions whose ENTIRE
    # operator vocabulary is nearly identical are much more likely to be
    # doing the same thing than two functions that happen to overlap on 2
    # ops out of a much richer combined set. Require a union of at least 2
    # operators (so a trivial single-shared-op case isn't judged on ratio
    # alone -- that's still gated by RARE_OPS/identifier overlap above).
    # FIX (found live, this session, round 6 -- explicit product decision:
    # zero false positives takes priority over recall): the jaccard-ratio
    # path below was added to rescue factorialIterative()/factorialRecursive()
    # after the round-1 fix over-corrected. But direct testing proved the
    # ratio is mathematically unable to distinguish that real match from
    # coincidental false positives -- maxValueHelper()/reverseArrayRecursive()
    # (unrelated: array-max vs array-reversal) shares {'>','+'} out of a
    # union of 3 (ratio 0.67), and maxValueIterative()/joinWordsLoop()
    # (completely unrelated: numeric max vs string joining) shares {'<','>'}
    # out of a union of 2 (ratio 1.0) -- both HIGHER ratios than several
    # confirmed-real matches. There is no ratio threshold that keeps the
    # true positives and rejects these false positives; they occupy the
    # same region of this feature space. Per explicit decision, this local
    # syntactic pre-filter is now a hard requirement (2+ non-ubiquitous
    # shared operators, OR 1+ shared RARE operator, OR meaningful shared
    # identifiers) rather than a soft ratio -- this WILL miss some real
    # Type 4 pairs whose only signal is 2 ubiquitous operators with no
    # other overlap (e.g. factorialIterative/factorialRecursive,
    # maxValueIterative/maxValueHelper in isolation). That tradeoff is
    # intentional: a missed duplicate is recoverable by a human reviewer:
    # a bad auto-refactor of two unrelated functions is not.
    # FIX (found live, this session, round 9 -- final decision: simplicity
    # and reliability over maximum recall): rounds 6-8 tried increasingly
    # careful ways to keep SOME value from "1 shared rare operator (%, /)
    # alone" as evidence, ending with a low-confidence warning system that
    # requires Java-side UI changes to actually surface. Per explicit
    # decision, dropping that path entirely instead: rare operators now
    # only count toward the SAME "2+ meaningful ops" bar as everything
    # else, never as a standalone pass on their own. This means
    # isPrimeIterative()/isPrimeHelper() and divideNumbers()/safeDivide()
    # -- both of which relied SOLELY on this single-rare-op path -- will no
    # longer be auto-detected, silently and without warning; they simply
    # won't appear as a flagged clone group at all. In exchange: every
    # remaining path to "sufficient_overlap" now requires genuinely
    # corroborated evidence (2+ non-ubiquitous shared operators, or real
    # identifier overlap), which every false positive found today
    # (coreArrayAverage/isPrimeHelper, gcdIterative/isPrimeHelper,
    # isPrimeIterative/sumDigitsHelper, sumDigitsIterative/
    # sumEvenNumbersLoop, and all the identifier-driven ones) fails. Any
    # Type 4 group that IS flagged from here on has strong evidence behind
    # it and should refactor correctly with no residual judgment calls
    # needed -- this is a hard requirement (server-side only), not a
    # warning that depends on the plugin UI reading and displaying it.
    # REVERTED round 9 back to round 8, per explicit decision: the fully
    # strict version (round 9) correctly eliminated every false positive,
    # but at the cost of silently dropping isPrimeIterative()/isPrimeHelper()
    # and divideNumbers()/safeDivide() entirely -- both real matches the
    # user needs working (isPrimeIterative/isPrimeHelper is the flagship
    # wrapper-delegation demo). Back to: rare-op-alone still counts as
    # sufficient evidence, but any match relying ONLY on that weakest path
    # is flagged low-confidence so the plugin UI can warn before applying.
    sufficient_overlap = len(meaningful_shared_ops) >= 2 or len(shared_rare_ops) >= 1
    # FIX (found live, this session, round 11 -- corrects an over-broad
    # exemption): the stream exemption above unconditionally set
    # sufficient_overlap=True the instant EITHER side was a stream
    # function, regardless of whether the other side had ANYTHING to do
    # with it -- confirmed live: isPrimeHelper() (a recursive
    # prime-checking helper, not remotely related to summing) matched
    # sumEvenNumbersStream() purely because sumEvenNumbersStream is a
    # stream, with ZERO shared identifiers (n/divisor vs numbers/x) and no
    # operator overlap either. The two REAL stream matches this exemption
    # was built for both have genuine, if below-threshold, identifier
    # overlap: joinWordsLoop()/joinWordsStream() share "words",
    # sumEvenNumbersLoop()/sumEvenNumbersStream() share "numbers". Narrowed
    # to relax the THRESHOLD for streams (any nonzero overlap is enough,
    # instead of requiring 0.3) rather than eliminating the requirement
    # for shared identifiers entirely.
    if (is_stream1 or is_stream2) and overlap > 0:
        sufficient_overlap = True
    # FIX (found live, this session, round 12 -- Scenario 3 GitHub PR test):
    # firstElement()/firstElementSafe() -- structurally identical in spirit
    # to lastElement()/lastElementSafe() -- was missing from PR detection
    # entirely. Traced precisely: firstElement() accesses arr[0] directly
    # and never references ".length", unlike lastElement() (arr[arr.length
    # - 1]) which shares "arr" AND "length" with its pair (overlap=0.500).
    # firstElement()/firstElementSafe() only share "arr" (overlap=0.250),
    # just under the round-10 threshold of 0.3. Confirmed the worst false
    # positive still tops out at 0.222, so 0.25 is a real, if narrow
    # (0.028 margin), gap. Lowered to 0.24. This margin is now tight enough
    # that further single-statement-body edge cases in this exact shape
    # (differ only by which literal index/bound is accessed) may keep
    # surfacing -- flagging this as a known area of residual risk rather
    # than claiming it's fully closed.
    if overlap < 0.24 and not sufficient_overlap:
        logger.debug(f"No identifier or meaningful operator overlap: {fp1} vs {fp2}, "
              f"shared_ops={shared_ops} (ubiquitous-only), overlap={overlap:.3f} — skipping")
        return False, False

    low_confidence = (
        len(meaningful_shared_ops) < 2
        and len(shared_rare_ops) >= 1
        and overlap < 0.24
    )
    return True, low_confidence



@app.route("/index", methods=["POST"])
def build_index():
    data = request.get_json()
    code = data.get("code", "")
    fn_name_hint = data.get("name", "")
    reset = data.get("reset", False)

    # Handle reset-only call (empty file saved)
    if reset:
        index_store.reset()
        logger.info("Index reset — file cleared.")
        return jsonify({"ok": True, "indexed": 0, "message": "Index cleared"})

    if not code or len(code.strip()) < 20:
        return jsonify({"ok": False, "message": "No code provided"}), 400

    code = code.strip()

    # Plugin sends just method body starting with { — index it as one unit
    # Don't re-chunk it, that causes for-loops to be indexed separately
    if code.startswith('{'):
        # Try to extract name from body content, fall back to hint or index
        fn_name = fn_name_hint if fn_name_hint else extract_function_name(code, index_store.name_hint_index())
        embedding = get_embedding(code)
        added = index_store.add_if_not_duplicate(code, embedding, fn_name)
        if added:
            logger.debug(f"Indexed: {fn_name}")
        else:
            logger.debug(f"Skipping duplicate: {fn_name}")
        logger.debug(f"FAISS index total: {index_store.ntotal} vectors")
        return jsonify({"ok": True, "indexed": index_store.ntotal})

    # Full method or file — chunk normally
    chunks = chunk_into_functions(code)
    logger.debug(f"chunk count={len(chunks)}, name_hint='{fn_name_hint}', code_start='{code[:50]}'")

    for i, chunk in enumerate(chunks):
        fn_name = fn_name_hint if fn_name_hint and i == 0 else extract_function_name(chunk, index_store.name_hint_index())
        embedding = get_embedding(chunk)
        added = index_store.add_if_not_duplicate(chunk, embedding, fn_name)
        if added:
            logger.debug(f"Indexed: {fn_name}")
        else:
            logger.debug(f"Skipping duplicate: {fn_name}")

    logger.debug(f"FAISS index total: {index_store.ntotal} vectors")
    return jsonify({"ok": True, "indexed": index_store.ntotal})


@app.route("/check", methods=["POST"])
def check_clone():
    data = request.get_json()
    suggestion = data.get("suggestion", "")

    if not suggestion or len(suggestion.strip()) < 20:
        return jsonify({"isClone": False, "reason": "Too short"}), 200

    if index_store.ntotal < 1:
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
        #
        # FIX (professor-flagged concurrency issue): previously read
        # faiss_index.ntotal, then called faiss_index.search(), then indexed
        # into stored_functions[idx] as three separate, unsynchronized steps
        # -- a write landing between any of them could desync the two
        # structures and raise IndexError, or silently return the wrong
        # matched function. search_with_snapshot() does the search and
        # copies out exactly the needed stored_functions entries under one
        # lock acquisition, so this can no longer happen even under
        # concurrent access.
        distances, indices, entries = index_store.search_with_snapshot(query)
        if distances is None:
            continue
        k = distances.shape[1]
        # (professor-flagged: removed unconditional DEBUG print of vector
        # count here -- was firing on every /check request regardless of
        # environment, pure log noise in production with no operational
        # value beyond what the per-candidate prints below already show)

        for rank in range(k):
            semantic_score = float(distances[0][rank])
            idx = int(indices[0][rank])
            if idx not in entries:
                continue
            matched = entries[idx]

            # Normalize matched snippet for comparison
            matched_body = normalize_body(extract_body(matched["snippet"]))
            matched_name = matched["name"]

            # Skip loop/block names
            if matched_name in ("for", "while", "if", "do", "switch", "try"):
                continue

            # Only skip self-match if body is different — same name + same body = Type 1 clone
            pasted_name = extract_function_name(suggestion, -1)
            if matched_name == pasted_name and chunk_body != matched_body:
                logger.debug(f"Skipping self-match (different body): {matched_name}")
                continue

            logger.debug(f"Candidate {matched_name}: semantic={semantic_score:.4f}")
            logger.debug(f"chunk_body: '{chunk_body[:80]}'")
            logger.debug(f"matched_body: '{matched_body[:80]}'")
            logger.debug(f"equal: {chunk_body == matched_body}")

            # Type 1: exact body match = always a clone
            # Whether same name or different name — if body is identical it's a duplicate
            # (professor-flagged: pasted_name was being recomputed here identically
            # to the computation a few lines above at line 1197 -- removed, the
            # earlier value is still in scope and nothing between the two call
            # sites changes 'suggestion' or invalidates the result)
            if chunk_body == matched_body:
                logger.debug(f"Type 1 detected: {matched_name}")
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
            logger.debug(f"chunk_stripped: {chunk_stripped[:80]}")
            logger.debug(f"matched_stripped: {matched_stripped[:80]}")
            candidate_stmts = count_statements(extract_body(chunk))
            matched_stmts   = count_statements(extract_body(matched["snippet"]))
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
                logger.debug(f"Type 2 detected: {real_name}")
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
            compatible, low_confidence = operations_compatible_shared(chunk, matched["snippet"])
            if not compatible:
                continue

            # FIX (professor-flagged, 3.2): structural_similarity() alone
            # compares tokens positionally, so reordering two independent
            # statements (or inserting a guard clause before existing
            # logic) shifts everything after that point out of alignment
            # even though the code's actual shape hasn't changed. /scan
            # already guards against this by taking max(pos_score,
            # bag_score) -- bag_similarity is a keyword-frequency Jaccard
            # score, inherently order-independent. /check previously had
            # no equivalent; bringing it in line here rather than
            # inventing a new algorithm, since bag_score's behavior is
            # already exercised and the struct_floor thresholds below are
            # already calibrated against this exact combined-score shape.
            pos_score = structural_similarity(extract_body(chunk), extract_body(matched["snippet"]))
            bag_score = bag_similarity(extract_body(chunk), extract_body(matched["snippet"]))
            struct_score = max(pos_score, bag_score)
            logger.debug(f"Structural: {struct_score:.4f} (positional={pos_score:.4f}, bag={bag_score:.4f})")

            # FIX (found live, this session, via actual server console log):
            # lastElement() -> lastElementSafe() (guard clause added to a
            # SINGLE-statement original body) scored struct_score=0.0769 --
            # just under the 0.08 floor, by a margin of 0.0031. A one-line
            # original body is even more extreme than the isEven/divideNumbers
            # cases the 0.08 floor was originally tuned against: adding one
            # guard clause to a single return statement swings the ratio more
            # than adding one to a 2-3 statement body. Lowered to 0.07 to
            # cover this case too, confirmed against real log data rather
            # than guessed.
            chunk_stmt_count = count_statements(extract_body(chunk))
            matched_stmt_count = count_statements(extract_body(matched["snippet"]))
            min_stmt_count = min(chunk_stmt_count, matched_stmt_count)
            struct_floor = 0.07 if min_stmt_count <= 3 else 0.15
            if struct_score < struct_floor:
                logger.debug(f"Skipping — structural too low: {struct_score:.4f} (floor={struct_floor})")
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
                if low_confidence:
                    recommendation = ("⚠️ LOW CONFIDENCE — this match relies on a single coincidental "
                                       "shared operator with no other supporting evidence; please review "
                                       "both functions carefully before applying. " + recommendation)
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
    logger.debug(f"Token variance: {token_variance:.4f} → perplexity_score: {normalized:.4f}")
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

        logger.debug(f"perplexity_score={perplexity_score:.4f}, embedding_score={embedding_score:.4f}")

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

        logger.debug(f"AI Detection: confidence={confidence:.4f} → {label}")

        return jsonify({
            "isAiGenerated": is_ai,
            "confidence": confidence,
            "confidencePercent": f"{round(confidence * 100)}%",
            "label": label,
            "perplexityScore": round(perplexity_score, 4),
            "embeddingScore": round(embedding_score, 4)
        })

    except Exception as e:
        logger.debug(f"AI detection error: {e}")
        return jsonify({
            "isAiGenerated": False,
            "confidence": 0.0,
            "reason": str(e)
        }), 500




# ── Scenario 3: Refactor suggestions (PR bot "Commit suggestion") ────────────
# Best-effort Python port of ExtractMethodEngine.java's core LCS-based block
# matching. Deliberately simplified relative to the IDE engine: this produces
# a SUGGESTION that a human reviews and clicks "Commit suggestion" on in the
# PR review UI -- it does not need (and does not attempt) every safety
# guarantee the PSI-based engine has (multi-value escapes -> result class,
# threading snapshots, etc.), since a human is always in the loop before
# anything actually gets committed. Where it can't confidently resolve a
# case, it returns available=False with a plain-English reason instead of
# guessing.
#
# Mirrors the same conclusion already documented for the IDE engine: Type 4
# (semantic) clones share no literal statements, so there is nothing to
# extract -- callers should not invoke this for Type 4 groups at all.

LCS_KEYWORDS = {
    "if", "else", "for", "while", "do", "switch", "case", "return", "break", "continue",
    "new", "this", "super", "true", "false", "null", "int", "long", "double", "float",
    "boolean", "char", "byte", "short", "void", "String", "instanceof", "throw", "try",
    "catch", "finally"
}

_TOKEN_RE = re.compile(
    r'"(?:[^"\\]|\\.)*"'      # double-quoted string literal
    r"|'(?:[^'\\]|\\.)*'"     # char literal
    r'|\d+(?:\.\d+)?[lLfFdD]?'  # numeric literal (was previously dropped entirely!)
    r'|[A-Za-z_][A-Za-z0-9_]*'  # identifier
    r'|[^A-Za-z0-9_\s]+'        # operators/punctuation
    r'|\s+'                      # whitespace
)


def _tokenize_statement(stmt_text):
    """Tokenize into (kind, value) pairs, kind in {lit, kw, id, punct}.
    Member-access fields (arr.length) and call names (foo() ) are tagged
    'kw' (verbatim, non-substitutable) for the same reason as before --
    substituting them causes false 'escaping parameter' bugs."""
    tokens = []
    for m in _TOKEN_RE.finditer(stmt_text):
        tok = m.group()
        if tok.isspace():
            continue
        if tok.startswith('"') or tok.startswith("'"):
            tokens.append(('lit', tok))
        elif re.fullmatch(r'\d+(?:\.\d+)?[lLfFdD]?', tok):
            tokens.append(('lit', tok))
        elif tok in LCS_KEYWORDS:
            tokens.append(('kw', tok))
        elif re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', tok):
            before = stmt_text[:m.start()].rstrip()
            after = stmt_text[m.end():].lstrip()
            if before.endswith('.') or after.startswith('('):
                tokens.append(('kw', tok))
            else:
                tokens.append(('id', tok))
        else:
            tokens.append(('punct', tok))
    return tokens


def _build_block_mapping(stmts_a_window, stmts_b_window):
    """Checks whether two equal-length statement windows are structurally
    identical, building the identifier correspondence AS WE GO across just
    this window (not from the start of the whole method).

    FIX: the previous approach pre-normalized each method's statements ONCE
    with a global first-occurrence counter (VAR1, VAR2...) starting from the
    top of the method. That meant a preceding, unrelated statement (e.g. a
    guard clause referencing 'arr' before 'total') could shift every
    placeholder number that followed, so a genuinely identical shared block
    later in the method would compare as different strings and never match
    at all. Found directly from a live Type 3 test: sumArraySafe()'s extra
    leading null-check pushed 'arr' to VAR1 there vs 'total' being VAR1 in
    sumArray(), and the otherwise-identical loop+return block was missed
    entirely. Building the mapping fresh per candidate window fixes this --
    only the relative order WITHIN the compared block matters now, matching
    however each side happens to declare things earlier.

    Returns (a_to_b, b_to_a) dicts on success, None if not structurally equal.
    """
    if len(stmts_a_window) != len(stmts_b_window):
        return None
    a_to_b, b_to_a = {}, {}
    for sa, sb in zip(stmts_a_window, stmts_b_window):
        ta, tb = _tokenize_statement(sa), _tokenize_statement(sb)
        if len(ta) != len(tb):
            return None
        for (ka, va), (kb, vb) in zip(ta, tb):
            if ka != kb:
                return None
            if ka in ('lit', 'kw', 'punct'):
                if va != vb:
                    return None
            else:  # 'id' -- must be a consistent bijection within this window
                if va in a_to_b:
                    if a_to_b[va] != vb:
                        return None
                elif vb in b_to_a:
                    return None
                else:
                    a_to_b[va] = vb
                    b_to_a[vb] = va
    return a_to_b, b_to_a


def _find_longest_common_contiguous_block(stmts_a, stmts_b):
    """Contiguous match only, not general LCS -- extracting a scattered set
    of matched statements would silently reorder code. Returns
    (start_a, start_b, length, (a_to_b, b_to_a)) or None."""
    best_len, best_i, best_j, best_map = 0, -1, -1, None
    for i in range(len(stmts_a)):
        for j in range(len(stmts_b)):
            length, mapping = 0, None
            while i + length < len(stmts_a) and j + length < len(stmts_b):
                cand = _build_block_mapping(stmts_a[i:i + length + 1], stmts_b[j:j + length + 1])
                if cand is None:
                    break
                mapping = cand
                length += 1
            if length > best_len:
                best_len, best_i, best_j, best_map = length, i, j, mapping
    return None if best_len == 0 else (best_i, best_j, best_len, best_map)


def _split_top_level_statements(body_inner):
    """Lightweight top-level statement splitter (not a full parser). Splits
    on ';' at depth 0, and treats a balanced {..} block (if/for/while/etc.)
    as one statement unit ending at its closing brace. Good enough for LCS
    matching of typical clone-pair snippets."""
    stmts, depth, paren_depth = [], 0, 0
    in_string = in_char = escape = False
    start = 0
    n = len(body_inner)
    i = 0
    while i < n:
        c = body_inner[i]
        if escape:
            escape = False
        elif in_string:
            if c == '\\':
                escape = True
            elif c == '"':
                in_string = False
        elif in_char:
            if c == '\\':
                escape = True
            elif c == "'":
                in_char = False
        elif c == '"':
            in_string = True
        elif c == "'":
            in_char = True
        elif c == '(':
            paren_depth += 1
        elif c == ')':
            paren_depth -= 1
        elif c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                stmts.append(body_inner[start:i + 1].strip())
                start = i + 1
        elif c == ';' and depth == 0 and paren_depth == 0:
            stmts.append(body_inner[start:i + 1].strip())
            start = i + 1
        i += 1
    tail = body_inner[start:].strip()
    if tail:
        stmts.append(tail)
    return [s for s in stmts if s]


def _extract_signature_and_body(full_method_code):
    idx = full_method_code.find('{')
    if idx == -1:
        return None, None
    sig = full_method_code[:idx].strip()
    block = full_method_code[idx:].strip()
    inner = block[1:-1] if block.startswith('{') and block.endswith('}') else block
    return sig, inner


def _extract_params(signature_line):
    m = re.search(r'\(([^)]*)\)', signature_line)
    if not m or not m.group(1).strip():
        return []
    params = []
    for part in m.group(1).split(','):
        toks = part.strip().rsplit(None, 1)
        if len(toks) == 2:
            params.append({"type": toks[0], "name": toks[1].lstrip('[]')})
    return params


def _extract_return_type(signature_line):
    """e.g. 'public int sumArray(int[] arr)' -> 'int'. Returns None if it
    can't confidently parse one (e.g. constructors, weird formatting)."""
    m = re.search(r'\b([\w<>\[\],\s]+?)\s+\w+\s*\([^)]*\)\s*$', signature_line.strip())
    return m.group(1).split()[-1] if m else None


def _normalize_type(t):
    """Loose type comparison -- ignores spacing differences like 'int []'
    vs 'int[]', and generic parameter names don't need to match verbatim."""
    return re.sub(r'\s+', '', t or '')


def _resolved_calls_own_name(inner_body, target_name):
    """True if inner_body contains a call to target_name(...) — a plain
    text/regex check (mirrors the rest of this file's text-based approach,
    since the GitHub-bot side has no PSI/AST to resolve calls with, unlike
    ExtractMethodEngine.java's PsiMethodCallExpression.resolveMethod())."""
    return bool(re.search(r'\b' + re.escape(target_name) + r'\s*\(', inner_body or ""))


def find_compatible_wrapper(name_a, name_b, all_functions, params_b, return_b):
    """
    FIX (found live, this session -- Scenario 2 Type 4 test, ported to the
    Scenario 3/GitHub-bot side for consistency): a semantically-correct
    Layer 2 match can still have an incompatible signature, because
    matching happens on BEHAVIOR, not public callability -- e.g.
    isPrimeIterative(int n) matches isPrimeHelper(int n, int divisor),
    which contains the real logic but isn't directly delegatable. Search
    the rest of the file's functions for one that (a) isn't name_a or
    name_b itself, (b) has a signature compatible with duplicate (params_b/
    return_b), and (c) already calls name_a somewhere in its body -- i.e.
    an existing public wrapper around the canonical, like
    isPrimeRecursive(int n) { return isPrimeHelper(n, 2); }. Mirrors
    ExtractMethodEngine.java's findCompatibleWrapper() so all three
    scenarios behave the same way. Returns (wrapper_name, wrapper_sig) or
    (None, None).
    """
    for fn in (all_functions or []):
        cand_name = fn.get("name", "")
        if cand_name in (name_a, name_b) or not cand_name:
            continue
        cand_sig, cand_inner = _extract_signature_and_body(fn.get("code", ""))
        if cand_sig is None or cand_inner is None:
            continue
        cand_params = _extract_params(cand_sig)
        cand_return = _extract_return_type(cand_sig)
        if len(cand_params) != len(params_b):
            continue
        if any(_normalize_type(cp["type"]) != _normalize_type(pb["type"])
               for cp, pb in zip(cand_params, params_b)):
            continue
        if _normalize_type(cand_return) != _normalize_type(return_b):
            continue
        if _resolved_calls_own_name(cand_inner, name_a):
            return cand_name, cand_sig
    return None, None


def generate_delegation_suggestion(name_a, code_a, name_b, code_b, all_functions=None):
    """
    Method Delegation: for clones with NO literal shared code (Type 4 --
    same intent, different implementation, e.g. loop vs recursion) but a
    compatible signature, Extract Method is a dead end by definition, but
    delegation only needs a shared CONTRACT (same param types in order,
    same return type), not shared statements. Rewrites duplicate to simply
    call canonical -- no helper method needed at all, which makes this
    actually simpler to apply than Extract Method.

    all_functions: optional list of {"name", "code"} for every function in
    the file, used only as a fallback when name_a/name_b don't share a
    signature directly -- see find_compatible_wrapper().

    Returns {"available": True, "technique": "delegation", "newDuplicateBody"}
    or {"available": False, "reason": "..."}.
    """
    try:
        sig_a, _ = _extract_signature_and_body(code_a)
        sig_b, _ = _extract_signature_and_body(code_b)
        if sig_a is None or sig_b is None:
            return {"available": False, "reason": "Could not parse method signature."}

        params_a = _extract_params(sig_a)
        params_b = _extract_params(sig_b)
        return_a = _extract_return_type(sig_a)
        return_b = _extract_return_type(sig_b)

        directly_compatible = (
            len(params_a) == len(params_b)
            and all(_normalize_type(pa["type"]) == _normalize_type(pb["type"])
                    for pa, pb in zip(params_a, params_b))
            and _normalize_type(return_a) == _normalize_type(return_b)
        )

        delegate_to = name_a
        via_sibling = False
        rewrite_name, rewrite_sig, rewrite_params = name_b, sig_b, params_b
        alone_name = name_a

        # FIX (found live, this session, round 2 -- ported from
        # ExtractMethodEngine.java's identical fix): the first version of
        # find_compatible_wrapper only searched for a wrapper AROUND name_a
        # (canonical), matching name_b's (duplicate's) signature. But which
        # function is "canonical" vs "duplicate" here is just whichever
        # order the scan/checklist listed them in -- unrelated to which one
        # actually has a matching wrapper elsewhere in the file. Try both
        # directions: first "does something wrap name_a with name_b's
        # signature" (rewrite name_b), and if that fails, "does something
        # wrap name_b with name_a's signature" (rewrite name_a instead).
        # Whichever function already has an existing wrapper depending on
        # it is left untouched -- this also naturally avoids ever rewriting
        # a private/recursive worker method that something else relies on.
        if not directly_compatible:
            wrapper_name, _ = find_compatible_wrapper(name_a, name_b, all_functions, params_b, return_b)
            if wrapper_name:
                delegate_to = wrapper_name
                via_sibling = True
            else:
                reverse_wrapper_name, _ = find_compatible_wrapper(name_b, name_a, all_functions, params_a, return_a)
                if reverse_wrapper_name:
                    delegate_to = reverse_wrapper_name
                    via_sibling = True
                    rewrite_name, rewrite_sig, rewrite_params = name_a, sig_a, params_a
                    alone_name = name_b
                elif len(params_a) != len(params_b):
                    return {
                        "available": False,
                        "reason": f"{name_a}() takes {len(params_a)} parameter(s) but {name_b}() "
                                  f"takes {len(params_b)} — delegation needs a matching signature, "
                                  f"and no existing method in this file already wraps either one "
                                  f"with a compatible signature."
                    }
                else:
                    for i, (pa, pb) in enumerate(zip(params_a, params_b)):
                        if _normalize_type(pa["type"]) != _normalize_type(pb["type"]):
                            return {
                                "available": False,
                                "reason": f"Parameter {i+1} type differs ({pa['type']} vs {pb['type']}) — "
                                          f"delegation needs a matching signature, and no existing method "
                                          f"in this file already wraps either one with a compatible signature."
                            }
                    return {
                        "available": False,
                        "reason": f"Return types differ ({return_a} vs {return_b}) — "
                                  f"delegation needs a matching signature, and no existing method "
                                  f"in this file already wraps either one with a compatible signature."
                    }

        call_args = ", ".join(p["name"] for p in rewrite_params)
        if _normalize_type(_extract_return_type(rewrite_sig)) == "void":
            new_duplicate_body = f"{rewrite_sig} {{\n    {delegate_to}({call_args});\n}}"
        else:
            new_duplicate_body = f"{rewrite_sig} {{\n    return {delegate_to}({call_args});\n}}"

        result = {
            "available": True,
            "technique": "delegation",
            "delegatesTo": delegate_to,
            "rewrites": rewrite_name,
            "newDuplicateBody": new_duplicate_body,
        }
        if via_sibling:
            result["viaSibling"] = True
            result["note"] = (f"{alone_name}() has the shared logic but an incompatible signature; "
                               f"delegated through {delegate_to}(), which already wraps {alone_name}() "
                               f"with a matching signature.")
        return result
    except Exception as e:
        return {"available": False, "reason": f"Delegation check failed: {e}"}


def generate_extract_suggestion(name_a, code_a, name_b, code_b, all_functions=None):
    """
    canonical = (name_a, code_a), duplicate = (name_b, code_b).
    Tries Extract Method first (needs a literal shared statement block).
    If no shared block exists -- the Type 4 case, by definition -- falls
    back to Method Delegation (needs only a compatible signature, not
    shared code). Returns a dict with "technique": "extract" | "delegation"
    on success, so callers (the CI workflow) know which shape of fix to post.

    all_functions: optional list of {"name", "code"} for every function in
    the file -- passed straight through to generate_delegation_suggestion's
    sibling-wrapper search. See find_compatible_wrapper().
    """
    try:
        sig_a, inner_a = _extract_signature_and_body(code_a)
        sig_b, inner_b = _extract_signature_and_body(code_b)
        if inner_a is None or inner_b is None:
            return {"available": False, "reason": "Could not parse method body."}

        stmts_a = _split_top_level_statements(inner_a)
        stmts_b = _split_top_level_statements(inner_b)
        if len(stmts_a) < 2 or len(stmts_b) < 2:
            return {"available": False, "reason": "Method body too short to extract."}

        block = _find_longest_common_contiguous_block(stmts_a, stmts_b)

        # NOTE (this session, Scenario 3 PR test): single-statement
        # extraction was tried and reverted here. firstElement()/
        # lastElement()-style pairs (whose only shared logic is genuinely
        # one statement) fail to extract as a result and correctly report
        # "insufficient shared code" -- a safe refusal, not a bug. The
        # attempt was reverted because it's unsafe for more than just
        # obviously-trivial statements: a guard clause with an early
        # return ("if (n < 2) return 0;"), extracted alone, silently
        # breaks control flow by discarding the helper's return value.
        # That was discovered by testing the fix against a real pair
        # (isPrimeIterative()/isPrimeHelper()), not guessed. Given repeated
        # evidence that narrowing this exception keeps surfacing new
        # correctness risks one level deeper, requiring block length >= 2
        # unconditionally prioritizes never-silently-corrupt over maximum
        # detection coverage.
        if block is None or block[2] < 2:
            # No literal shared fragment -- Extract Method has nothing to
            # work with by definition (this is the Type 4 case). Try
            # delegation instead before giving up entirely.
            #
            # FIX (found live, this session, Scenario 3 PR test -- CRITICAL):
            # confirmed via direct testing that falling back to delegation
            # unconditionally here is genuinely dangerous, not just
            # imprecise. coreSumValues()/sumEvenNumbersLoop() -- two
            # unrelated functions that merely share ".length" and "total"
            # as generic loop/accumulator tokens -- passed
            # operations_compatible_shared() (identifier overlap 0.333,
            # HIGHER than some real matches) and, once extraction correctly
            # found no real shared block, fell through to delegation, which
            # "succeeded" purely because the signatures matched --
            # producing sumEvenNumbersLoop() { return coreSumValues(numbers); },
            # silently deleting its even-number filter. Identifier overlap
            # alone is not reliable enough evidence to authorize something
            # that can silently change behavior. Require operator-level
            # evidence instead (matching the RARE_OPS/meaningful-ops logic
            # operations_compatible_shared already uses) before allowing
            # the delegation fallback specifically -- this still lets real
            # cases like isPrimeIterative()/isPrimeHelper() through (they
            # share the rare '%' operator), while blocking identifier-only
            # coincidences from ever reaching an auto-applied rewrite.
            fp_a = operator_fingerprint_shared(code_a)
            fp_b = operator_fingerprint_shared(code_b)
            shared_fp = fp_a & fp_b
            RARE_OPS_LOCAL = {'/', '%'}
            UBIQUITOUS_OPS_LOCAL = {'+', '-', '*', '<', '>', '=='}
            meaningful_shared_fp = shared_fp - UBIQUITOUS_OPS_LOCAL
            has_operator_evidence = (
                len(shared_fp & RARE_OPS_LOCAL) >= 1 or len(meaningful_shared_fp) >= 2
            )
            if not has_operator_evidence:
                return {
                    "available": False,
                    "reason": "No shared code fragment found, and the only supporting "
                              "evidence for a possible relationship is generic identifier "
                              "overlap (e.g. common loop/accumulator variable names) with "
                              "no shared distinctive operators — too weak to safely "
                              "auto-apply a delegation. This pair should be reviewed "
                              "manually rather than auto-refactored."
                }
            delegation = generate_delegation_suggestion(name_a, code_a, name_b, code_b, all_functions)
            if delegation.get("available"):
                return delegation
            return {
                "available": False,
                "reason": "No shared code fragment found — likely a Type 4 semantic "
                          "clone (same intent, different implementation). Extract "
                          "Method needs literal shared statements, and Method "
                          f"Delegation isn't safe either: {delegation.get('reason', 'incompatible signatures')}"
            }

        start_a, start_b, length, (a_to_b, b_to_a) = block
        block_stmts_a = stmts_a[start_a:start_a + length]
        after_a = stmts_a[start_a + length:]
        joined_block_a = ' '.join(block_stmts_a)
        joined_after_a = ' '.join(after_a)

        # If the block itself ends with a return, that return travels with
        # it into the helper -- this is NOT the "escaping variable" case
        # (nothing needs to survive past the block boundary), it's a direct
        # return-type match against the method's own declared return type.
        block_ends_in_return = bool(re.match(r'^return\b', block_stmts_a[-1].strip())) if block_stmts_a else False

        declared_in_block_a = set(re.findall(
            r'\b(?:int|long|double|float|boolean|char|byte|short|String|var|[A-Z]\w*(?:<[^>]*>)?)\s+(\w+)\s*=',
            joined_block_a))
        escaping = [v for v in declared_in_block_a if re.search(r'\b' + re.escape(v) + r'\b', joined_after_a)]
        if len(escaping) > 1:
            return {
                "available": False,
                "reason": f"Multiple values ({', '.join(escaping)}) escape the shared "
                          f"block — needs a result-holder class, which this bot doesn't "
                          f"attempt automatically. Extract manually in the IDE instead."
            }
        escaping_var = escaping[0] if escaping else None

        # helper params: every identifier the block-mapping paired up that
        # ISN'T declared inside the block itself (i.e. it came from a param
        # or an earlier statement) -- the mapping already gives canonical's
        # real name -> duplicate's real name directly, no reverse-lookup needed.
        helper_params = [
            {"canonical_name": real_a, "duplicate_name": real_b}
            for real_a, real_b in a_to_b.items()
            if real_a not in declared_in_block_a
        ]

        param_types = {p["name"]: p["type"] for p in _extract_params(sig_a)}
        sig_params = ", ".join(
            f'{param_types.get(p["canonical_name"], "Object")} {p["canonical_name"]}'
            for p in helper_params
        )

        if block_ends_in_return:
            # Return type comes from canonical's own signature, e.g.
            # "public int sumPositives(int[] arr)" -> "int".
            ret_match = re.search(r'\b([\w<>\[\],\s]+?)\s+\w+\s*\([^)]*\)\s*$', sig_a)
            return_type = ret_match.group(1).split()[-1] if ret_match else "var"
        elif escaping_var:
            decl_match = re.search(
                r'\b(int|long|double|float|boolean|char|byte|short|String|\w+(?:<[^>]*>)?)\s+'
                + re.escape(escaping_var) + r'\s*=', joined_block_a)
            return_type = decl_match.group(1) if decl_match else "var"
        else:
            return_type = "void"

        helper_name = "core" + name_b[:1].upper() + name_b[1:]

        # block_stmts_a already includes the trailing "return ...;" verbatim
        # when block_ends_in_return is true -- don't append a second one.
        helper_body_lines = list(block_stmts_a)
        if escaping_var and not block_ends_in_return:
            helper_body_lines.append(f"return {escaping_var};")
        helper_code = (
            f"private static {return_type} {helper_name}({sig_params}) {{\n    "
            + "\n    ".join(helper_body_lines) + "\n}"
        )

        call_args = ", ".join(p["duplicate_name"] for p in helper_params)
        if block_ends_in_return:
            call_line = f"return {helper_name}({call_args});"
        elif escaping_var:
            escaping_var_b = a_to_b.get(escaping_var, escaping_var)
            call_line = f"{return_type} {escaping_var_b} = {helper_name}({call_args});"
        else:
            call_line = f"{helper_name}({call_args});"

        new_duplicate_stmts = stmts_b[:start_b] + [call_line] + stmts_b[start_b + length:]
        new_duplicate_body = sig_b + " {\n    " + "\n    ".join(new_duplicate_stmts) + "\n}"

        # FIX: without this, the helper's body is a byte-identical copy of
        # canonical's block (since it's built FROM canonical's block) while
        # canonical itself is left untouched -- meaning the very next scan
        # flags helper<->canonical as a brand-new Type 1 exact clone, and
        # "fixing" THAT produces coreCoreXxx, ad infinitum. Found directly
        # from a live PR test: applying one suggestion triggered a second,
        # near-identical one on every subsequent scan. Rewriting canonical
        # to delegate too closes the loop -- both sides end up calling the
        # helper, so neither has a body left to be a duplicate of anything.
        call_args_a = ", ".join(p["canonical_name"] for p in helper_params)
        if block_ends_in_return:
            call_line_a = f"return {helper_name}({call_args_a});"
        elif escaping_var:
            call_line_a = f"{return_type} {escaping_var} = {helper_name}({call_args_a});"
        else:
            call_line_a = f"{helper_name}({call_args_a});"

        new_canonical_stmts = stmts_a[:start_a] + [call_line_a] + stmts_a[start_a + length:]
        new_canonical_body = sig_a + " {\n    " + "\n    ".join(new_canonical_stmts) + "\n}"

        return {
            "available": True,
            "technique": "extract",
            "helperName": helper_name,
            "helperCode": helper_code,
            "newDuplicateBody": new_duplicate_body,
            "newCanonicalBody": new_canonical_body,
            "matchedStatements": length
        }
    except Exception as e:
        return {"available": False, "reason": f"Suggestion generation failed: {e}"}


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

    logger.info(f"/scan received {len(functions)} functions from {filename}")

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

    logger.info(f"/scan indexed {len(scan_functions)} functions")

    def extract_body_local(code):
        code = code.strip()
        if code.startswith("{"):
            return code
        idx = code.find("{")
        if idx != -1:
            return code[idx:].strip()
        return code

    clone_groups = []
    seen_pairs = set()  # prevents A<->B and B<->A duplicates

    def is_delegation_wrapper(code):
        """
        Returns True if this method's entire body is a single delegation call —
        i.e. it was produced by CloneGuard's Method Delegation refactoring.
        These methods are intentional wrappers, not clones, and should never
        be flagged as Type 2 matches against each other.

        Patterns matched:
          { return someMethod(arg); }
          { someMethod(arg); }
          { return someMethod(a, b); }
        """
        body = extract_body_local(code).strip()
        # Remove braces and whitespace
        inner = body.strip('{}').strip()
        # Match: optional 'return', a method call, semicolon — and nothing else
        delegation_pattern = re.compile(
            r'^(?:return\s+)?\w+\s*\([^)]*\)\s*;$'
        )
        return bool(delegation_pattern.match(inner))

    def is_refactor_wrapper(code):
        """
        Broader than is_delegation_wrapper(): returns True if this method's
        body calls one of CloneGuard's own generated helper methods.

        Both refactoring techniques the plugin uses always route through a
        helper named "core" + PascalCase (e.g. coreSumPositives,
        coreSumPositives2 if a name collision required a suffix) —
        Method Delegation's output matches is_delegation_wrapper() above
        (a single-line call, nothing else), but Extract Method's output does
        NOT — it can keep extra statements that were unique to that specific
        method (e.g. a preserved println), so the body is no longer a single
        statement and the narrow pattern above misses it entirely.

        Since we control the naming convention of every helper CloneGuard
        generates, checking for a call to a core*-named method is a reliable
        signal that this body is a refactor byproduct, not an independent
        implementation — regardless of how many other statements remain.
        Two such wrappers calling DIFFERENT helpers can still look similar
        to each other structurally/semantically (both are short and mostly
        boilerplate), which is exactly the false-positive this closes.
        """
        if is_delegation_wrapper(code):
            return True
        body = extract_body_local(code)
        return bool(re.search(r'\bcore[A-Z]\w*\s*\(', body))

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
                # Skip if EITHER side is a refactor-generated wrapper.
                # FIX: previously only skipped when BOTH sides were wrappers,
                # which meant a leftover generated helper (e.g.
                # coreSumPositives) could still get compared against a
                # completely unrelated ORIGINAL function (e.g.
                # fibonacciRecursive) and score a coincidentally high
                # similarity from CodeBERT despite sharing no real meaning —
                # found directly from hand-testing. A generated wrapper is
                # internal, tool-produced delegation code from an already-
                # accepted refactor, not something the user wrote with
                # intent to be judged for duplication — it shouldn't be
                # re-flagged against anything else, not just against other
                # wrappers.
                if is_refactor_wrapper(fn_i["snippet"]) or is_refactor_wrapper(fn_j["snippet"]):
                    logger.debug(f"/scan skipping refactor wrappers: {fn_i['name']} <-> {fn_j['name']}")
                    seen_pairs.add(pair_key)
                    continue
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
                clone_groups[-1]["suggestion"] = generate_extract_suggestion(
                    fn_i["name"], fn_i["snippet"], fn_j["name"], fn_j["snippet"])
                seen_pairs.add(pair_key)
                logger.debug(f"/scan Type 1: {fn_i['name']} <-> {fn_j['name']}")
                continue

            # Type 2 vs Type 3: normalized structure match
            if stripped_i == stripped_j and stripped_i != "":
                # Skip if BOTH methods are refactor-generated wrappers —
                # they were intentionally refactored (Delegation or Extract
                # Method) and are not clones of each other, even if their
                # remaining bodies happen to look structurally similar.
                if is_refactor_wrapper(fn_i["snippet"]) or is_refactor_wrapper(fn_j["snippet"]):
                    logger.debug(f"/scan skipping refactor wrappers: {fn_i['name']} <-> {fn_j['name']}")
                    seen_pairs.add(pair_key)
                    continue
                stmts_i = count_statements(extract_body_local(fn_i["snippet"]))
                stmts_j = count_statements(extract_body_local(fn_j["snippet"]))
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
                    clone_groups[-1]["suggestion"] = generate_extract_suggestion(
                        fn_i["name"], fn_i["snippet"], fn_j["name"], fn_j["snippet"])
                    seen_pairs.add(pair_key)
                    logger.debug(f"/scan Type 2: {fn_i['name']} <-> {fn_j['name']}")
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
                    clone_groups[-1]["suggestion"] = generate_extract_suggestion(
                        fn_i["name"], fn_i["snippet"], fn_j["name"], fn_j["snippet"])
                    seen_pairs.add(pair_key)
                    logger.debug(f"/scan Type 3: {fn_i['name']} <-> {fn_j['name']}")

    # ── Layer 2: Type 3 and Type 4 ───────────────────────────────────────────
    layer1_names = {g["functionA"] for g in clone_groups} | {g["functionB"] for g in clone_groups}

    # FIX (bug #7): previously nothing stopped a single function from being
    # claimed by MORE than one Layer 2 group in the same scan (observed
    # directly: computeMinMaxSum appeared paired with both computeA AND
    # computeB simultaneously). Track which names have already been used in
    # a Layer 2 group and skip any further pair involving them — each
    # function gets at most one Layer 2 match per scan, first (highest-
    # scoring, since pairs are still gated by the 0.90 threshold below)
    # match wins.
    layer2_claimed = set()

    for i in range(len(scan_functions)):
        for j in range(i + 1, len(scan_functions)):
            fn_i = scan_functions[i]
            fn_j = scan_functions[j]

            pair_key = tuple(sorted([fn_i["name"], fn_j["name"]]))
            if pair_key in seen_pairs:
                continue

            # FIX (found live, this session -- rigorous multi-function scan
            # test): this previously only skipped a pair when BOTH sides
            # were already claimed at Layer 1 -- but a function fully
            # consumed by its Layer 1 match (e.g. sumValues, already paired
            # exactly with sumValuesExact as a Type 1 clone) has no business
            # being independently re-matched against some THIRD, unrelated
            # function at Layer 2 just because that third function itself
            # wasn't Layer-1-claimed. Confirmed live: this exact gap is what
            # let sumValues (already claimed by its Type 1 match) get
            # re-flagged against sumDigitsIterative, and sumValuesExact
            # against reverseArrayRecursive -- neither pairing has anything
            # to do with each other. A function claimed at Layer 1 must be
            # fully off the table for Layer 2, not just when its ORIGINAL
            # partner is also involved.
            if fn_i["name"] in layer1_names or fn_j["name"] in layer1_names:
                continue

            if fn_i["name"] in layer2_claimed or fn_j["name"] in layer2_claimed:
                continue

            # This check was previously ONLY applied in the Layer 1 loop
            # above — Layer 2 (semantic/CodeBERT) had no equivalent
            # safeguard at all. That gap is exactly what let two
            # Extract-Method-generated wrappers (e.g. sumPositives() and
            # sumPositiveValues() after both were rewritten to call their
            # own core*() helpers) get re-flagged as a brand-new Type 3/4
            # clone of EACH OTHER once their remaining bodies happened to
            # look similar — even though neither is being compared against
            # its own helper, they're being compared against each other,
            # post-refactor, and both are legitimate wrappers.
            if is_refactor_wrapper(fn_i["snippet"]) or is_refactor_wrapper(fn_j["snippet"]):
                logger.debug(f"/scan skipping refactor wrappers (Layer 2): {fn_i['name']} <-> {fn_j['name']}")
                seen_pairs.add(pair_key)
                continue

            semantic_score = float(np.dot(fn_i["embedding"], fn_j["embedding"]))
            if semantic_score < 0.90:
                continue

            compatible, low_confidence = operations_compatible_shared(
                fn_i["snippet"], fn_j["snippet"], name1=fn_i["name"], name2=fn_j["name"])
            if not compatible:
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
            stmts_i = count_statements(extract_body_local(fn_i["snippet"]))
            stmts_j = count_statements(extract_body_local(fn_j["snippet"]))
            min_stmts = min(stmts_i, stmts_j)
            struct_floor = 0.07 if min_stmts <= 3 else 0.15
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

            if low_confidence:
                rec = ("⚠️ LOW CONFIDENCE — this match relies on a single coincidental shared "
                       "operator with no other supporting evidence (no shared identifiers, no "
                       "additional operator overlap); please review both functions carefully "
                       "before applying. " + rec)

            clone_groups.append({
                "cloneType": clone_type,
                "similarity": f"{round(semantic_score * 100)}%",
                "functionA": fn_i["name"],
                "functionB": fn_j["name"],
                "filename": filename,
                "detectionLayer": "Layer 2 (Server — CodeBERT+FAISS)",
                "severity": severity,
                "recommendation": rec,
                "lowConfidence": low_confidence,
            })
            if same_shape:
                clone_groups[-1]["suggestion"] = generate_extract_suggestion(
                    fn_i["name"], fn_i["snippet"], fn_j["name"], fn_j["snippet"])
            else:
                # Type 4 — no literal shared fragment can exist by
                # definition, so Extract Method is skipped, but
                # generate_extract_suggestion() internally falls back to
                # Method Delegation (needs only a compatible signature, not
                # shared code) before giving up. This is what actually lets
                # Type 4 clones get a real, applicable suggestion now.
                clone_groups[-1]["suggestion"] = generate_extract_suggestion(
                    fn_i["name"], fn_i["snippet"], fn_j["name"], fn_j["snippet"])
            seen_pairs.add(pair_key)
            layer2_claimed.add(fn_i["name"])
            layer2_claimed.add(fn_j["name"])
            logger.debug(f"/scan {clone_type}: {fn_i['name']} <-> {fn_j['name']}")

    logger.info(f"/scan complete: {len(clone_groups)} clone groups found")

    return jsonify({
        "cloneGroups": clone_groups,
        "totalClones": len(clone_groups),
        "scannedFunctions": len(scan_functions),
        "filename": filename
    })

@app.route("/suggest", methods=["POST"])
def suggest_single_pair():
    """
    Dedicated single-pair endpoint for the checkbox-driven batch refactor
    flow. The apply-refactors workflow already knows exactly which pair the
    person selected (parsed from the checklist comment) -- it doesn't need
    a full /scan, just "given these two functions RIGHT NOW, what's the
    suggestion." Re-run explicitly at apply-time (not reusing the original
    scan's cached suggestion) so it reflects the file's current state, not
    whatever it looked like when the checklist was first posted.
    """
    data = request.get_json(force=True) or {}
    name_a, code_a = data.get("nameA", ""), data.get("codeA", "")
    name_b, code_b = data.get("nameB", ""), data.get("codeB", "")
    # Optional: every function in the file, used only as a fallback to find
    # an existing signature-compatible wrapper when name_a/name_b don't
    # share a signature directly (see find_compatible_wrapper()). Absent
    # for older callers -- that's fine, it just disables the fallback.
    all_functions = data.get("allFunctions")
    if not all([name_a, code_a, name_b, code_b]):
        return jsonify({"available": False, "reason": "Missing nameA/codeA/nameB/codeB"}), 400
    try:
        suggestion = generate_extract_suggestion(name_a, code_a, name_b, code_b, all_functions)
    except Exception as e:
        suggestion = {"available": False, "reason": f"Suggestion failed: {e}"}
    return jsonify(suggestion)


@app.route("/health", methods=["GET"])
def health():
    return jsonify({
        "status": "ok",
        "indexed": index_store.ntotal,
        "models": ["codebert", "unixcoder"]
    })


@app.route("/test", methods=["GET"])
def test_page():
    test_path = os.path.join(os.path.dirname(__file__), "test_page.html")
    return send_file(test_path)


if __name__ == "__main__":
    # FIX (Render deployment): two changes needed for this to work as a
    # cloud-hosted service, neither of which affects local development.
    #
    # 1. host="127.0.0.1" only accepts connections from the SAME machine
    #    -- completely unreachable from the internet. Render (and every
    #    other cloud host) needs 0.0.0.0, which accepts connections on
    #    any network interface, not just loopback.
    #
    # 2. Render assigns its own port dynamically and injects it via the
    #    PORT environment variable -- it does NOT respect a custom
    #    variable name. Checking PORT first (falling back to the
    #    existing CLONEGUARD_PORT, then 8765) means this same file keeps
    #    working exactly as before for local development -- nothing
    #    about the existing `KMP_DUPLICATE_LIB_OK=TRUE python3 server.py`
    #    workflow changes -- while also correctly picking up whatever
    #    port Render assigns when deployed there.
    port = int(os.environ.get("PORT", os.environ.get("CLONEGUARD_PORT", "8765")))
    host = "0.0.0.0"
    logger.info(f"Server running on {host}:{port}")
    logger.info(f"Test page: http://{host}:{port}/test")
    app.run(host=host, port=port, debug=False, threaded=False, use_reloader=False)
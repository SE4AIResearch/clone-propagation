# CloneGuard — AI-Assisted Code Clone Detection & Refactoring

CloneGuard is an IntelliJ IDEA plugin, backed by a Python detection server,
that finds duplicated and AI-generated code in real time and automatically
refactors it. It works across three scenarios: live paste detection, full-file
scanning with one-click refactoring, and an automated GitHub PR bot.

## Repository Structure

plugin/       — IntelliJ IDEA Java plugin (Gradle project)
server/       — Python Flask server (CodeBERT + FAISS, Layer 2 detection + AI detection)
test-repo/    — Sample Java files + GitHub Actions workflow (clone-check.yml) for Scenario 3

---

## The 3 Scenarios

### Scenario 1 — Real-Time Paste Detection
Watches the editor (`InlineSuggestionListener`) for pasted Java methods
(≥30 characters, matching method-shape heuristics). On detection, shows
`CloneWarningDialog` with an AI-generated badge, a clone-type badge, and
three actions:
- **Use Existing Function** — deletes the pasted duplicate and navigates
  to the original
- **Accept Anyway** — leaves the pasted code as-is
- **Dismiss** — leaves the pasted code as-is

Handles body-only pastes (when the developer typed the signature and only
pasted the method body) by reconstructing the full method before checking.
**Prevention only — no refactoring in this scenario.**

### Scenario 2 — Full File Scan + Refactoring
`ScanFileAction` → `FileScannerService.scanFile()` extracts every method in
the open file (PSI-based, regex fallback), sends them to the server's
`/scan` endpoint, and displays results as cards in the CloneGuard tool
window (`CloneGuardToolWindowFactory`). Each card shows the clone type,
similarity %, detection layer, and a **Delegate →** button that rewrites
the duplicate method to call the canonical one.

If the server is unreachable, falls back to `FileScannerService.runLocalLayer1()`,
which uses `LocalCloneDetector` directly — Layer 1 only (see Known
Limitations below).

### Scenario 3 — GitHub PR Bot
A GitHub Actions workflow (`test-repo/.github/workflows/clone-check.yml`)
runs on every PR update, scans changed Java files against the server (via
an ngrok tunnel to the developer's local instance), and posts a PR comment
listing detected clone groups and per-pair recommendations. Warns at ≤3
clones, blocks merge at >3.

---

## Detection Layers

**Layer 1 — Local (`LocalCloneDetector.java`)**
Runs entirely client-side, no server needed.
- **Type 1 (Exact Clone):** Karp-Rabin hash over whitespace-normalized code
- **Type 2 (Renamed Clone):** hash over identifier-normalized code (all
  non-keyword identifiers replaced with `VAR`/`FUNC`), with a control-flow
  shape check (loop vs. recursion) to avoid false matches; falls back to
  token-similarity comparison (≥90%) if exact hash doesn't match
- Also used by `CloneIndexService.detectAiLocal()` for a 7-signal rule-based
  AI-generated-code heuristic (identifier length, camelCase ratio, comments,
  enhanced for-loops, method chaining, stream/lambda usage) — runs
  independently of clone detection and works even with no indexed functions

**Layer 2 — Server (`server.py`, Flask + CodeBERT + FAISS)**
- **Type 3 (Near-Miss Clone):** structural similarity, operator/identifier
  overlap heuristics
- **Type 4 (Semantic Clone):** CodeBERT embeddings + FAISS similarity search
  — catches functionally-equivalent code with completely different
  implementations (e.g. a loop vs. a stream `.filter().count()`)
- **AI detection model** (`/detect-ai`, UniXcoder-based) — combined with
  the local rule-based heuristic in `CloneIndexService.detect()`; if either
  signal is confident (>60%), the AI badge is shown, preferring whichever
  signal has higher confidence

---

## Refactoring

**Method Delegation** (`triggerMethodDelegation()`) — the only technique
currently wired into the "Delegate →" button, used for **all four clone
types**. Rewrites the duplicate method's body to a single delegation call:
```java
return canonicalMethod(args);
```
API-preserving (callers never break), behaviorally guaranteed identical to
the canonical method, requires no semantic understanding to apply safely.
Tradeoff: any logic unique to the duplicate (e.g. extra logging) is
discarded, not preserved.

**Extract Method** (`triggerExtractMethod()`, `findCommonStatements()`,
`normalizeForLCS()`) — fully implemented but **not currently reachable from
the UI**. Uses LCS (longest common subsequence) over identifier-normalized
statements to extract only the *shared* logic into a new private helper,
letting each method keep its own unique statements. More faithful than
delegation, but more complex and not yet wired into the refactor button's
routing — `triggerRefactor()`'s switch statement sends Type 3 straight to
Method Delegation.

**Type 4 suggestion** — after delegating a semantic clone, a follow-up
dialog recommends reviewing both implementations (e.g. prefer iterative
over recursive for large inputs).

---

## Setup

### Running the server
```bash
cd server
pip install -r requirements.txt
KMP_DUPLICATE_LIB_OK=TRUE python3 server.py
```
Runs on `http://localhost:8765`. The plugin's `PythonServerClient` expects
it there by default (hardcoded `BASE_URL`).

### Building & installing the plugin
```bash
cd plugin
./gradlew buildPlugin -x test -x buildSearchableOptions
```
In IntelliJ: **Settings → Plugins → ⚙️ → Install Plugin from Disk**, select
`plugin/build/distributions/CloneGuard-1.1.0.zip`, restart the IDE.

### Scenario 3 (GitHub PR bot) setup
```bash
ngrok http 8765
```
Update the `CLONEGUARD_SERVER_URL` secret under this repo's
**Settings → Secrets and variables → Actions** with the new ngrok URL (no
trailing slash). Open a PR against `test-repo/` to trigger the bot.

---

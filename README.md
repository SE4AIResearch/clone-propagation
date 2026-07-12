# CloneGuard — AI-Assisted Code Clone Detection & Refactoring

CloneGuard is an IntelliJ IDEA plugin, backed by a Python detection server,
that finds duplicated and AI-generated code in real time and automatically
refactors it. It works across three scenarios: live paste detection, full-file
scanning with one-click refactoring, and an automated GitHub PR bot.

## Repository Structure

plugin/       — IntelliJ IDEA Java plugin (Gradle project)
server/       — Python Flask server (CodeBERT + FAISS, Layer 2 detection + AI detection)
test-repo/    — Sample Java files for local testing
.github/workflows/
clone-check.yml      — Scenario 3: PR scan + refactor checklist comment
apply-refactors.yml  — Scenario 3: checkbox-driven batch refactor apply

---

## The 3 Scenarios

### Scenario 1 — Real-Time Paste Detection + Refactoring
Watches the editor (`InlineSuggestionListener`) for pasted Java methods
(≥30 characters, matching method-shape heuristics). On detection, shows
`CloneWarningDialog` with an AI-generated badge, a clone-type badge, and
three actions:
- **Use Existing Function** — deletes the pasted duplicate and navigates
  to the original
- **Accept Anyway** — leaves the pasted code in place, adds a persistent
  notification with a refactor action
- **Dismiss** — leaves the pasted code in place, adds the same notification

Handles body-only pastes (when the developer typed the signature and only
pasted the method body) by reconstructing the full method before checking.

The notification's refactor action routes by clone type — **"Refactor with
Extract Method"** for Types 1–3, **"Refactor with Method Delegation"** for
Type 4 — and both call into the same `ExtractMethodEngine` Scenario 2 uses,
so a fix in one scenario's refactoring logic applies to both.

### Scenario 2 — Full File Scan + Refactoring
`ScanFileAction` → `FileScannerService.scanFile()` extracts every method in
the open file (PSI-based, regex fallback), sends them to the server's
`/scan` endpoint, and displays results as cards in the CloneGuard tool
window (`CloneGuardToolWindowFactory`). Each card shows the clone type,
similarity %, detection layer, and a refactor button labeled **"Extract →"**
(Types 1–3) or **"Delegate →"** (Type 4). Applying a refactor triggers an
automatic rescan, so results are always derived fresh from the current file
— this also correctly handles overlapping clone groups (e.g. one method
matching two different duplicates) without going stale.

If the server is unreachable, falls back to `FileScannerService.runLocalLayer1()`,
which uses `LocalCloneDetector` directly — Layer 1 only (Types 1–2; see
Known Limitations below).

### Scenario 3 — GitHub PR Bot
A GitHub Actions workflow (`.github/workflows/clone-check.yml`) runs on
every PR update, scans changed Java files against the server (via an ngrok
tunnel to the developer's local instance), and posts a PR comment listing
detected clone groups, per-pair recommendations, and a checklist of
available refactors. Warns at ≤3 clones, blocks merge at >3.

Checking boxes on the checklist and commenting `/refactor` triggers a
second workflow, `apply-refactors.yml`, which:
- Re-verifies each checked item against the **current** file content
  (not the original scan) immediately before applying anything
- Detects when two checked items would touch the same function and applies
  only the first, skipping the rest with a clear reason instead of risking
  a broken file
- Commits directly to the PR branch rather than posting GitHub suggestion
  blocks, so it isn't limited to lines that are part of the diff

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
  overlap heuristics, with pre-filters for return type, branching shape,
  loop nesting depth, and arithmetic operator family (these pre-filters are
  automatically exempted when either side is recursive or stream-based, so
  they don't reject genuine loop-vs-recursion Type 4 pairs before scoring)
- **Type 4 (Semantic Clone):** CodeBERT embeddings + FAISS similarity search
  — catches functionally-equivalent code with completely different
  implementations (e.g. a loop vs. a stream `.filter().count()`, or
  iterative vs. recursive)
- **AI detection model** (`/detect-ai`, UniXcoder-based) — combined with
  the local rule-based heuristic in `CloneIndexService.detect()`; if either
  signal is confident (>60%), the AI badge is shown, preferring whichever
  signal has higher confidence

---

## Refactoring: Two Techniques, Applied Correctly Per Type

CloneGuard uses whichever refactoring technique is actually correct for
the clone type detected — it does not apply one technique to everything.

**Extract Method** (`ExtractMethodEngine.extract()`) — the technique for
**Types 1, 2, and 3**. Finds the literal shared block of statements via a
windowed structural-equality matcher: the identifier correspondence is
rebuilt fresh for each candidate window rather than numbered once globally
from the top of the method, which specifically avoids missing a match when
one method has a preceding statement (like a guard clause) that the other
doesn't — a global numbering scheme would otherwise shift every placeholder
after it out of sync. The shared block is extracted into a new private
helper method; both the canonical and duplicate methods are rewritten to
call it, preserving each method's own unique surrounding logic.

**Method Delegation** (`ExtractMethodEngine.delegate()`) — the technique
for **Type 4 only**. Semantic clones share no literal code by definition,
so Extract Method has nothing to work with. Delegation instead checks
whether the two methods have a *compatible signature* (same parameter
types in order, same return type) — if so, it rewrites the duplicate
method's body to a single delegation call:
```java
return canonicalMethod(args);
```
The canonical method is left completely untouched — no helper is created,
since none is needed. This is API-preserving and requires no semantic
understanding to apply safely, but it does mean any logic unique to the
duplicate (e.g. extra logging, a null guard) is discarded unless the
signatures genuinely allow it to be preserved — Type 3's guard-clause case
is handled correctly by Extract Method precisely because that logic *is*
preserved as part of the duplicate's own remaining code, not delegated away.

Both techniques are implemented twice — once in the IntelliJ plugin
(`ExtractMethodEngine.java`, PSI-based, shared by Scenario 1 and 2) and
once in the GitHub bot (`server.py`, text-based, for Scenario 3) — kept
behaviorally consistent with each other by design, though maintained as
two separate implementations since the plugin has real PSI access and the
CI runner does not.

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
the resulting `.zip` from `plugin/build/distributions/`, restart the IDE.
Bump the `version` in `build.gradle` before rebuilding if testing a change
— reinstalling over an unchanged version number can silently no-op.

### Scenario 3 (GitHub PR bot) setup
```bash
ngrok http 8765
```
Update the `CLONEGUARD_SERVER_URL` secret under this repo's
**Settings → Secrets and variables → Actions** with the new ngrok URL (no
trailing slash). Under **Settings → Actions → General → Workflow
permissions**, ensure **"Read and write permissions"** is selected —
`apply-refactors.yml` needs to be able to commit directly to PR branches.
Open a PR touching a `.java` file to trigger the bot.

---
*Powered by CloneGuard — AI-Assisted Code Clone Detection*

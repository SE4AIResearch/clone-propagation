# CloneGuard

CloneGuard is an IntelliJ IDEA plugin, paired with a hosted detection
server, that catches code clones the moment they're introduced — pasted,
typed, or accepted from an AI suggestion — and offers a safe, automated
refactor for each. It also tracks real, tool-verified code-quality metrics
as those refactors are applied over time.

---

## Quick Start (for using the plugin)

This is genuinely the whole thing. The plugin talks to a persistently
hosted detection server by default — no local Python, no server setup, no
API key to configure.

1. Get the plugin `.zip` (GitHub release, or built from source — see
   [Building from source](#building-from-source) below)
2. IntelliJ IDEA → **Settings/Preferences → Plugins → gear icon (⚙) →
   Install Plugin from Disk**
3. Select the `.zip`, then **restart IntelliJ** when prompted
4. Start using it:
   - Paste code into a Java file — CloneGuard checks it automatically
   - Or **Tools → CloneGuard → Scan Current File** to check a whole file at once

Nothing else is required. **IntelliJ IDEA Ultimate is required** (Community
edition doesn't expose the plugin APIs CloneGuard depends on).

**One thing worth knowing:** the very first request after a period of
inactivity can take a little longer than usual, while the hosted server
spins back up — that's expected, not a bug.

---

## What it actually does

### 1. Paste / inline-suggestion interception
The moment you paste or accept a sufficiently large chunk of Java code,
CloneGuard checks it against every method already in the file. If it's a
clone, a warning dialog appears with three options — dismiss it, accept it
anyway, or jump to the existing method it duplicates. If you keep the
duplicate, a persistent notification stays with a one-click refactor
action, so you're never forced to decide in the moment.

### 2. Full-file scan
**Tools → CloneGuard → Scan Current File** runs a complete pass over the
open file, surfacing every clone group and every method that's a candidate
for being pushed down out of an overly broad superclass. Results appear in
a dedicated tool window with a **Refactor →** button per group.

### 3. AI-generated code detection
Separately from clone detection, CloneGuard can flag whether a given block
of code looks AI-generated. This runs as two independent systems, not one
combined check: the server's `/detect-ai` endpoint (UniXcoder-based) is
treated as primary whenever it's reachable and confident, and a lighter
local heuristic (based on identifier length, casing, comments, loop style,
and method chaining) only decides the outcome when the server is uncertain
or unreachable.

### 4. Trend Dashboard
A second tab in the same tool window shows, per file, how quality has
actually changed across every refactor you've applied — lines of code
before/after, which refactoring techniques you've used, which clone types
you've fixed, and (when SciTools Understand is installed locally) real
Cyclomatic Complexity, Weighted Methods per Class, Coupling, and
Inheritance metrics for the file's current state. A toggle also switches
to a whole-project average across every file with recorded history.

---

## GitHub Pull Request Integration

Beyond the IDE, CloneGuard also runs as a **team-level gate on every pull
request**, via two GitHub Actions workflows in `.github/workflows/`. This
is fully independent of the IntelliJ plugin — it works even for
contributors who don't have CloneGuard installed locally at all.

### `clone-check.yml` — scans every PR automatically

Triggers on every PR **open, update, or reopen** that touches a `.java`
file. For each changed file:

1. Extracts every method (via regex, no local Java parser needed) and
   sends them to the same detection server's `/scan` endpoint the plugin
   uses
2. For any clone with an available Extract Method suggestion, tries to
   post it as a **real, clickable GitHub "Commit suggestion"** — but only
   where GitHub actually allows one: the duplicate method's lines have to
   genuinely be part of this PR's diff. Where they aren't, the suggestion
   is included as a plain (non-clickable) code block in the summary
   comment instead, with an explanation why.
3. Posts a single PR summary comment: a table of every clone group found
   (type, similarity, detection layer, severity), a **checklist** of
   selectable clones, and a recommendation per group.
4. **Blocks the merge check** if more than 3 clone groups are found
   (fails the workflow); otherwise passes with a warning if any were found
   at all.

### `apply-refactors.yml` — applies your selections

Comment **`/refactor`** on the PR to act on the checklist from
`clone-check.yml`. This workflow:

1. Reads the most recent CloneGuard checklist comment and parses which
   boxes are checked
2. **Re-verifies each selection against the file's current content**
   before touching anything — so if two checked items would edit the same
   method, only the first is applied; the rest are skipped with a clear
   explanation rather than silently conflicting
3. Commits the applied refactors directly to the PR branch and pushes —
   this is deliberately a direct file edit rather than another GitHub
   suggestion comment, since a direct edit isn't limited to lines already
   part of the diff
4. Comments back exactly what was applied and what was skipped (and why)
5. The push itself re-triggers `clone-check.yml`, so the checklist
   refreshes automatically

### Required repository secrets

Both workflows need two GitHub Actions secrets configured under
**Settings → Secrets and variables → Actions**:

| Secret | Purpose |
|--------|---------|
| `CLONEGUARD_SERVER_URL` | Same detection server the plugin uses by default — `https://cloneguard-server.onrender.com`, or your own self-hosted URL |
| `CLONEGUARD_API_KEY` | Matches the plugin's configured key; omit (or leave blank) if your server is running unauthenticated |

`GITHUB_TOKEN` (used for posting comments and, in `apply-refactors.yml`,
pushing commits) is provided automatically by GitHub Actions — no setup
needed for that one.

---

## How detection works

| Type | Description | Method |
|------|-------------|--------|
| Type 1 | Exact duplicate, whitespace aside | Layer 1 — local, SHA-256 content hash |
| Type 2 | Identical structure, renamed identifiers | Layer 1 — local, `VAR`/`FUNC`-normalized identifier hash |
| Type 3 | Near-miss — same core logic, minor differences | Layer 2 — server, CodeBERT + FAISS |
| Type 4 | Semantic clone — same intent, different implementation | Layer 2 — server, CodeBERT + FAISS |

Type 1/2 run entirely inside the IDE process, no network call — this is
what makes the paste-time check fast enough to run interactively. For Type
1, the method body is whitespace-normalized and hashed directly. For Type
2, every identifier is replaced with one of two placeholder tokens
depending on how it's used — `VAR` for a variable reference, `FUNC` for an
identifier immediately followed by `(` — before hashing, so a renamed copy
still collapses to the same hash as the original. Type 3/4 are sent to the
detection server's `/scan` (full-file) or `/check` (single-paste)
endpoints, which run both layers internally and return final clone groups.

### The detection server

By default, the plugin points at a hosted instance:
```
https://cloneguard-server.onrender.com
```
running CodeBERT and UniXcoder for the embedding-based Layer 2 detection.
Authentication is bearer-token based; a working key ships baked into the
plugin by default, so a fresh install works immediately with no
configuration. Both the **server URL** and the **API key** can be changed
at **Settings/Preferences → Tools → CloneGuard** — useful if you're
self-hosting the server or running your own deployment.

---

## Refactoring techniques

CloneGuard picks the correct mechanical fix for the clone type found, and
shows exactly what it's about to do before applying it.

| Technique | Applies when |
|-----------|--------------|
| **Extract Method** | Two methods in the same class share at least two literal statements (Type 1/2/3) |
| **Method Delegation** | Two methods have no shared code but a compatible signature (typically Type 4) |
| **Pull Up Method** | Two methods live in sibling subclasses sharing a common, user-defined superclass, and are structurally identical (exact match, or identical apart from renamed local variables — never renamed fields) |
| **Push Down Method** | A method sits on a superclass with two or more direct subclasses, but is only ever referenced from exactly one of them |

Each technique includes its own safety checks — parameter mismatches,
variables that escape the shared block, conditional returns without a
guaranteed path, naming collisions — and refuses rather than guesses
whenever it can't prove the refactor is safe. Extract Method in particular
requires at least two statements of literal overlap before running
automatically; a near-miss clone whose only shared logic is a single
statement is still detected and reported, just not auto-refactored, since
a single shared line (like a guard clause) isn't always safe to lift in
isolation.

---

## The Trend Dashboard

Each **Scan Current File** opens a *session* — a snapshot of the file's
current state. If you apply one or more refactors before the next scan,
that session becomes a permanent data point the moment the next scan
begins; scanning again with nothing changed records nothing. A
paste-triggered refactor (Scenario 1) can also open its own session if
none is already active, so trend data isn't limited to file-scan-driven
workflows. History is appended to `.cloneguard/metrics.jsonl` in your
project root.

Per session, the dashboard shows a before/after lines-of-code chart, a
refactor-type breakdown, a clone-type breakdown, and — if **SciTools
Understand** is installed locally — the current state's real design
metrics:

| Metric | Full name | What it measures | Understand column |
|--------|-----------|-------------------|--------------------|
| **CC** | Cyclomatic Complexity | Decision paths through the most complex method in the class | `MaxCyclomatic` |
| **WMC** | Weighted Methods per Class | Sum of every method's complexity in the class | `SumCyclomatic` |
| **CBO** | Coupling Between Objects | How many other classes this class references | `CountClassCoupled` |
| **DIT** | Depth of Inheritance Tree | How many levels up the class hierarchy this class sits | `MaxInheritanceTree` |
| **NOC** | Number of Children | How many other classes directly extend this class | `CountClassDerived` |

(LOC is tracked too, but from the plugin's own line count, shown on the
chart above rather than in this metrics row.)

A toggle in the dashboard switches between the current file's own history
and a whole-project average, computed from the latest session of every
file that has at least one recorded scan.

**Understand is entirely optional** and completely separate from the
detection server — it's a locally installed desktop tool used only to
power this one dashboard row. Everything else in CloneGuard works fully
without it, and if it isn't installed the dashboard simply shows
"Understand not available" for that row.

### Installing Understand (optional)

Understand is a paid static-analysis tool from SciTools, with free
educational licenses available.

1. Go to [scitools.com](https://scitools.com) and download Understand for
   your OS — Windows, macOS, or Linux are all supported.
2. Sign up for a license (educational licenses are free for students and
   faculty) and activate it on first launch.
3. Confirm the command-line tool works, by opening a terminal and running:
   ```
   und version
   ```
   This should print a version string. If it doesn't, Understand's `bin`
   folder likely isn't the one CloneGuard is looking for — see the exact
   paths below.

CloneGuard looks for the `und` executable at these locations, in order,
falling back to a bare `und` command last (in case it's genuinely on
`PATH` for the process that launched the IDE):

| OS | Path CloneGuard checks |
|----|------------------------|
| macOS | `/Applications/Understand.app/Contents/MacOS/und` |
| Windows | `C:\Program Files\SciTools\bin\pc-win64\und.exe` |
| Linux | `/usr/bin/und` and `/opt/scitools/bin/linux64/und` |

If your install landed somewhere else, the simplest fix is a symlink (or,
on Windows, copying `und.exe`) into one of the paths above.

**A real gotcha, especially on macOS:** adding `und` to your shell's
`PATH` does **not** make it visible to IntelliJ. IntelliJ is a GUI app,
and GUI apps launched from Finder/Spotlight/the Dock don't inherit `PATH`
changes made in a terminal — this is exactly why CloneGuard checks
Understand's known install locations directly instead of trusting `PATH`.
If you routinely launch IntelliJ from a terminal (e.g. `idea .`), your
shell's `PATH` *would* carry through in that specific case, but the
built-in path list above works regardless of how IntelliJ was launched.

If Understand isn't installed at all, or the executable genuinely can't be
found, the dashboard simply shows "Understand not available" for that row
— nothing else in CloneGuard is affected.

---

## Building from source

For contributors, or to build the plugin `.zip` yourself:

```bash
# 1. Get the repo
cd ~/Desktop
git clone <repo-url> clone-propagation
cd clone-propagation

# 2. Open the plugin subfolder in IntelliJ IDEA Ultimate
#    File → Open → select clone-propagation/plugin → "Open as Project"
#    Trust the project, wait for Gradle sync (first run downloads the
#    IntelliJ Platform SDK — several hundred MB)

# 3. Run it in a sandboxed IDE instance
cd plugin
./gradlew runIde
```

`runIde` opens a second, separate IntelliJ window with the plugin already
installed — the safe way to test without touching your main IDE install.

To produce a distributable `.zip`:
```bash
./gradlew buildPlugin
```
Output: `plugin/build/distributions/CloneGuard-<version>.zip`

### Build requirements

- **IntelliJ IDEA Ultimate** (the plugin targets `type = 'IU'` in
  `build.gradle` — Community edition cannot build or run it)
- **Java 17+** — the IntelliJ Platform itself has run on JBR 17 since the
  2022.3 release line; IntelliJ's own bundled JDK satisfies this
- **IntelliJ Platform version**: currently targets `2026.1.3`
  (`sinceBuild 233`, `untilBuild 261.*` in `build.gradle`) — if your
  installed IntelliJ version falls outside that range, update these
  values to match before building

### Self-hosting the detection server

Only needed if you're developing against server-side changes, or want your
own private deployment instead of the shared hosted instance:

```bash
cd server
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt
python server.py
curl http://localhost:8765/health
```

`requirements.txt` pulls in: Flask + Flask-CORS (the HTTP server itself),
a CPU-only build of PyTorch (via `--extra-index-url
https://download.pytorch.org/whl/cpu` — deliberately CPU rather than CUDA,
since this needs to run on ordinary hosting like Render's free/standard
tiers, not GPU infrastructure), Transformers (loads CodeBERT and
UniXcoder), FAISS-CPU (the similarity search behind Layer 2 detection),
and NumPy.

Then point the plugin at it: **Settings/Preferences → Tools → CloneGuard**,
set Server URL to `http://localhost:8765`, and clear the API key field
(local dev instances typically run unauthenticated).

---

## Trying it out

**Paste interception:** open a Java file with an existing method, paste a
near-duplicate elsewhere in the same file — a warning should appear.

**Full scan:** open a file with two or more similar methods, then
**Tools → CloneGuard → Scan Current File**. Click **Refactor →** on any
group in the Scan Results tab to apply the suggested fix.

**Trend Dashboard:** after applying at least one refactor and scanning
again, open the **Trend Dashboard** tab (next to Scan Results).

---

## Troubleshooting

**Gradle sync fails** — confirm Project SDK is Java 17+: File → Project
Structure → SDK.

**Can't open the project / build fails oddly** — confirm you're using
IntelliJ IDEA **Ultimate**, not Community.

**`runIde` opens but plugin isn't visible** — check that the sandbox IDE's
own Help → About version falls within `build.gradle`'s `sinceBuild`/
`untilBuild` range.

**Paste interception never fires** — this relies on JetBrains AI Assistant
being enabled (Settings → Tools → AI Assistant); it also only fires on
insertions large enough to plausibly be a full method or class.

**Getting 401s from the detection server** — your configured API key
(Settings → Tools → CloneGuard) doesn't match what the server expects.
Clear it if you're pointing at an unauthenticated local server, or confirm
you're using the correct key for a hosted deployment.

**First request after inactivity is slow** — expected. The hosted server
spins down after idle periods (Render's standard behavior) and takes a
moment to wake back up.

**Trend Dashboard says "Understand not available"** — confirm `und
version` works from a terminal, check the install-location table above for
your OS, and see the PATH gotcha above — this is the most common cause,
especially on macOS.

**Understand's numbers look like they're from before your refactor, not
after** — make sure the file is explicitly saved to disk before the
"after" Understand analysis runs. Understand reads the file straight from
disk as an external process; if IntelliJ hadn't flushed its in-memory edit
yet, it would silently analyze stale content.

**PR check never runs** — confirm `CLONEGUARD_SERVER_URL` and
`CLONEGUARD_API_KEY` are set as repository secrets (Settings → Secrets and
variables → Actions), and that the PR actually touches a `.java` file —
the workflow's `paths` filter skips everything else.

**`/refactor` comment does nothing** — the workflow only looks for the
*most recent* CloneGuard checklist comment on that PR. If `clone-check.yml`
hasn't posted one yet (e.g. it's still running, or found zero clones),
there's nothing to act on.

**Inline "Commit suggestion" isn't clickable, shows as a plain code block
instead** — this is expected, not a bug, whenever the duplicate method's
lines aren't part of this specific PR's diff. GitHub only allows anchoring
a review-comment suggestion to lines actually touched by the diff; the
fallback code block exists precisely for this case.

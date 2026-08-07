# CloneGuard — Installation Guide

This guide covers everything needed to get CloneGuard fully running,
across all three ways it operates: the IntelliJ plugin, the optional
self-hosted detection server, and the GitHub Actions Pull Request
integration. Most users only need the first section — the plugin talks to
a hosted server by default, so nothing else here is required to start
using it.

---

## What you actually need to install

| Piece | Required? | Why |
|-------|-----------|-----|
| **IntelliJ Plugin** | Yes | This is CloneGuard itself — everything else is optional |
| **Detection Server** | No | Only if self-hosting instead of using the default hosted instance |
| **GitHub Actions workflows** | No | Only if you want PR-level detection on a specific repository |
| **SciTools Understand** | No | Only if you want the Trend Dashboard's design-metrics row (CC/WMC/CBO/DIT/NOC) |

---

## 1. Installing the IntelliJ Plugin (required)

### Prerequisites

- **IntelliJ IDEA Ultimate** — Community edition does not expose the
  plugin APIs CloneGuard depends on, and the plugin cannot be built or run
  under it.
- No local Python, no server setup, and no API key configuration needed —
  the plugin talks to a persistently hosted detection server by default.

### Steps

1. Get the plugin `.zip`:
   - From a GitHub release on this repository, **or**
   - Built from source yourself (see [Section 4](#4-building-the-plugin-from-source-optional) below)
2. Open IntelliJ IDEA → **Settings/Preferences → Plugins → gear icon (⚙)
   → Install Plugin from Disk**
3. Select the `.zip` file
4. **Restart IntelliJ** when prompted
5. Confirm it's active: open **Tools** in the menu bar — you should see a
   **CloneGuard** submenu

### Verifying it works

- Open any `.java` file, paste in a method that duplicates one already in
  the file — a warning dialog should appear within a couple of seconds
- Or run **Tools → CloneGuard → Scan Current File** on a file with two or
  more similar methods

**Note:** the very first request after a period of inactivity can take a
little longer than usual, while the hosted server spins back up — this is
expected, not a sign anything's broken.

---

## 2. Self-Hosting the Detection Server (optional)

Only needed if you're developing against server-side changes, or want a
private deployment instead of the shared hosted instance.

### Prerequisites

- Python 3.9+
- `pip`

### Steps

```bash
cd server
python -m venv venv
source venv/bin/activate          # Windows: venv\Scripts\activate
pip install -r requirements.txt
python server.py
```

Verify it's running:
```bash
curl http://localhost:8765/health
```

### What gets installed

`requirements.txt` pulls in:

```
flask
flask-cors
--extra-index-url https://download.pytorch.org/whl/cpu
torch
transformers
faiss-cpu
numpy
```

- **Flask + Flask-CORS** — the HTTP server itself
- **PyTorch (CPU-only build)** — deliberately CPU rather than CUDA, so
  this can run on ordinary hosting (like Render's free/standard tiers)
  without needing GPU infrastructure
- **Transformers** — loads the CodeBERT and UniXcoder models
- **FAISS-CPU** — the similarity search behind Layer 2 detection
- **NumPy**

### Pointing the plugin at your self-hosted server

**Settings/Preferences → Tools → CloneGuard**:
- Server URL → `http://localhost:8765`
- API Key → leave blank (local dev instances typically run unauthenticated)

---

## 3. GitHub Actions Pull Request Integration (optional)

Adds CloneGuard as an automatic check on every Pull Request in a specific
repository — fully independent of the IntelliJ plugin, so it works even
for contributors who don't have CloneGuard installed at all.

### Steps

1. Copy both workflow files into the target repository's
   `.github/workflows/` folder:
   - `clone-check.yml`
   - `apply-refactors.yml`
2. Add two repository secrets under **Settings → Secrets and variables →
   Actions**:

   | Secret | Value |
   |--------|-------|
   | `CLONEGUARD_SERVER_URL` | The same server the plugin uses by default (`https://cloneguard-server.onrender.com`), or your own self-hosted URL |
   | `CLONEGUARD_API_KEY` | Matches your server's configured key; leave blank if the server is unauthenticated |

   (`GITHUB_TOKEN` is provided automatically by GitHub Actions — no setup
   needed for that one.)
3. Open or update any Pull Request that touches a `.java` file —
   `clone-check.yml` should run automatically and post a checklist
   comment within a minute or two.

### Using it

- Check the boxes on any clone groups you want fixed
- Comment `/refactor` on the PR
- `apply-refactors.yml` applies the selected fixes and commits them
  directly to the PR branch, then `clone-check.yml` re-runs automatically
  to confirm the fix

---

## 4. Building the Plugin From Source (optional)

Only needed for contributors, or if you want to build the `.zip` yourself
instead of using a release.

### Prerequisites

- **IntelliJ IDEA Ultimate**
- **Java 17+** (IntelliJ's own bundled JDK satisfies this)
- Target **IntelliJ Platform version 2026.1.3** (`sinceBuild 233`,
  `untilBuild 261.*` in `build.gradle`) — update these values in
  `build.gradle` first if your installed IntelliJ version falls outside
  that range

### Steps

```bash
# 1. Clone the repo
cd ~/Desktop
git clone <repo-url> clone-propagation
cd clone-propagation

# 2. Open the plugin subfolder in IntelliJ IDEA Ultimate
#    File → Open → select clone-propagation/plugin → "Open as Project"
#    Trust the project, wait for Gradle sync
#    (first run downloads the IntelliJ Platform SDK — several hundred MB)

# 3. Run it in a sandboxed IDE instance
cd plugin
./gradlew runIde
```

`runIde` opens a second, separate IntelliJ window with the plugin already
installed — the safe way to test without touching your main IDE install.

To produce a distributable `.zip` instead:
```bash
./gradlew buildPlugin
```
Output: `plugin/build/distributions/CloneGuard-<version>.zip`

---

## 5. Installing SciTools Understand (optional)

Only needed for the Trend Dashboard's design-metrics row (Cyclomatic
Complexity, Weighted Methods per Class, Coupling, Depth of Inheritance,
Number of Children). Everything else in CloneGuard works fully without it.

### Steps

1. Go to [scitools.com](https://scitools.com) and download Understand for
   your OS (Windows, macOS, and Linux are all supported)
2. Sign up for a license — free educational licenses are available for
   students and faculty — and activate it on first launch
3. Confirm the command-line tool works:
   ```
   und version
   ```
   This should print a version string.

### Where CloneGuard looks for it

CloneGuard checks these install locations directly, in order, rather than
relying on your shell's `PATH`:

| OS | Path |
|----|------|
| macOS | `/Applications/Understand.app/Contents/MacOS/und` |
| Windows | `C:\Program Files\SciTools\bin\pc-win64\und.exe` |
| Linux | `/usr/bin/und` and `/opt/scitools/bin/linux64/und` |

If your install is somewhere else, the simplest fix is a symlink (or, on
Windows, copying `und.exe`) into one of the paths above.

**Important gotcha, especially on macOS:** adding `und` to your shell's
`PATH` does **not** make it visible to IntelliJ. IntelliJ is a GUI app,
and GUI apps launched from Finder/Spotlight/the Dock don't inherit `PATH`
changes made in a terminal — this is exactly why CloneGuard checks the
known install locations above directly, instead of trusting `PATH`.

If Understand isn't installed, or can't be found, the Trend Dashboard
simply shows "Understand not available" for that row — nothing else in
CloneGuard is affected.

---

## Troubleshooting

**Gradle sync fails** — confirm Project SDK is Java 17+: File → Project
Structure → SDK.

**Can't open the project / build fails oddly** — confirm you're using
IntelliJ IDEA Ultimate, not Community.

**`runIde` opens but plugin isn't visible** — check that the sandbox IDE's
own Help → About version falls within `build.gradle`'s `sinceBuild`/
`untilBuild` range.

**Paste interception never fires** — relies on JetBrains AI Assistant
being enabled (Settings → Tools → AI Assistant); also only fires on
insertions large enough to plausibly be a full method or class.

**Getting 401s from the detection server** — your configured API key
(Settings → Tools → CloneGuard) doesn't match what the server expects.

**First request after inactivity is slow** — expected; the hosted server
spins down after idle periods and takes a moment to wake back up.

**Trend Dashboard says "Understand not available"** — confirm `und
version` works from a terminal, check the install-location table above,
and see the PATH gotcha — this is the most common cause, especially on
macOS.

**PR check never runs** — confirm `CLONEGUARD_SERVER_URL` and
`CLONEGUARD_API_KEY` are set as repository secrets, and that the PR
actually touches a `.java` file.

**`/refactor` comment does nothing** — the workflow only looks for the
*most recent* CloneGuard checklist comment on that PR.

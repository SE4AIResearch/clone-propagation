# CloneGuard

**AI-assisted code clone detection and automated refactoring for IntelliJ IDEA.**

CloneGuard catches code duplication at the moment it's created — as you paste code, when you scan a file, or the instant a Pull Request is opened, instead of waiting for a separate audit long after the clone has already shipped. When it finds one, it doesn't just flag it: a confirmed clone connects directly to automated refactoring, so most duplicates are resolved in a single action rather than requiring you to rewrite the code by hand.

---

## Why CloneGuard

AI coding assistants like GitHub Copilot and Cursor are very good at writing working code on demand but they have no visibility into the rest of your codebase. Ask one to write a function that sums an array, and it will, with no way of knowing an equivalent function already exists two files away. Traditional clone-detection tools don't help here either: they operate as a batch scan over a codebase that already exists, long after the moment a clone was actually introduced.

CloneGuard is built around closing that timing gap specifically detecting duplication close to the moment it's created, not after the fact.

---

## Features

- **Three detection points, not one** — real-time paste detection, on-demand file scanning, and automatic GitHub Pull Request review
- **Two-layer detection** — a fast local layer for exact and renamed clones, escalating to a CodeBERT-based semantic layer only when needed, for clones that look completely different but do the same thing
- **Four automated refactoring techniques** — Extract Method, Method Delegation, Pull Up Method, and Push Down Method, chosen automatically based on where the duplicate actually lives in your code
- **Refactoring-safety verification** — the step that edits your code independently re-checks that a fix is actually safe immediately before applying it, rather than blindly trusting what detection reported
- **Trend Dashboard** — tracks a file's quality over time: lines of code, duplication eliminated, which refactoring techniques were used, which clone types they fixed, and cyclomatic complexity, all computed from real code structure via IntelliJ's PSI
- **AI-generated-code advisory flag** — a lightweight, separate signal indicating pasted code may have come from an AI assistant, shown alongside (but not confused with) clone-detection results

---

## How It Works

CloneGuard is two components working together:

- **An IntelliJ IDEA plugin** (Java) — lives entirely in your IDE, watches for pasted code, runs scans on request, and applies refactorings
- **A Python backend server** (Flask) — handles the computationally heavier work: CodeBERT embeddings and FAISS similarity search for semantic clone detection

The plugin checks locally first; the server is only called when a deeper, semantic comparison is actually needed. A third, independent path — a GitHub Actions workflow calls the same server directly during Pull Request review, with no IDE involved at all.

---

## Getting Started

### Requirements

- IntelliJ IDEA (Community or Ultimate)
- A CloneGuard plugin build (see [Building the Plugin](#building-the-plugin) below)

### Installing the Plugin

1. Download the latest plugin `.zip` from [Releases](#) *(or build it yourself — see below)*
2. In IntelliJ: **Settings/Preferences → Plugins → ⚙️ → Install Plugin from Disk...**
3. Select the downloaded `.zip` and restart the IDE

No local server setup is required — the plugin is pre-configured to reach a persistently hosted detection server, so semantic (Type 3/4) detection works immediately after installation.

### Building the Plugin

```bash
git clone https://github.com/SE4AIResearch/clone-propagation.git
cd clone-propagation/plugin
./gradlew buildPlugin -x test -x buildSearchableOptions
```

The built plugin `.zip` will be in `plugin/build/distributions/`.

---

## Usage

### Scenario 1 — Real-Time Paste Detection

Just paste code into the editor as normal. If CloneGuard finds a match, a warning dialog appears immediately with the clone type, similarity score, and the matched function. From there you can **View Original**, **Refactor**, or **Ignore**.

### Scenario 2 — File Scan

**Tools → CloneGuard → Scan Current File**

Scans every method in the current file against every other, surfacing results in a dedicated tool window grouped by clone type, with a recommended fix for each.

### Scenario 3 — GitHub Pull Request Agent

Add the provided GitHub Actions workflows to your repository (`.github/workflows/clone-check.yml` and `apply-refactors.yml`). Every Pull Request touching Java files is scanned automatically; detected clones are posted as a checklist comment. Check the boxes for the clones you want fixed, then comment:

```
/refactor
```

The workflow re-verifies each selected item is still safe to refactor, applies the fix, and commits it directly to the branch.

One-time setup, for whoever adds this workflow to a repository (not something plugin users ever need to do): add your server's URL and API key as repository secrets.

On GitHub, go to your repository
Settings → Secrets and variables → Actions
Click New repository secret
Add the first secret:
Name: CLONEGUARD_SERVER_URL
Value: your hosted server's URL (e.g. https://cloneguard-server.onrender.com)
Click New repository secret again and add the second:
Name: CLONEGUARD_API_KEY
Value: your server's API key
Copy clone-check.yml and apply-refactors.yml from this repo's .github/workflows/ folder into the same path in your own repository
That's it — this is a one-time step per repository. Once both secrets are set, every Pull Request touching Java files is scanned automatically; individual reviewers and contributors never need to configure anything themselves.
---

## Configuration

Plugin settings are available under **Settings/Preferences → Tools → CloneGuard**:

| Setting | Description |
|---|---|
| Server URL | Address of the detection server (defaults to the hosted instance) |
| API Key | Authentication token sent with every server request |

---

## Refactoring Techniques

| Technique | Applies to |
|---|---|
| **Extract Method** | Type 1–3 clones within the same class |
| **Method Delegation** | Type 4 semantic clones |
| **Pull Up Method** | Type 1/2 clones duplicated across sibling subclasses sharing a superclass |
| **Push Down Method** | A superclass method only ever used by one subclass |

Pull Up and Push Down are only available in Scenarios 1 and 2 — GitHub's single-range comment interface can't express an edit spanning two separate locations, so Scenario 3 uses Extract Method and Method Delegation only.

---

## Known Limitations

- Pull Up Method does not support Type 3 (near-miss) clone pairs
- The AI-generated-code flag is an advisory signal based on five surface-level stylistic markers, not an independently corroborated determination
- Trend Dashboard sessions are tied to Scenario 2 scans; a workflow relying entirely on Scenario 1 paste-refactors without ever running a scan will show no trend data

---

## Repository Structure

```
clone-propagation/
├── plugin/              # IntelliJ IDEA plugin (Java)
├── server/               # Python Flask backend (CodeBERT + FAISS)
├── .github/workflows/    # Scenario 3 GitHub Actions
└── README.md
```

---


## Team

- Vaansh Virral Sanghrajka — [vsanghra@stevens.edu](mailto:vsanghra@stevens.edu)
- Prof. Eman Alomar — [ealomar@stevens.edu](mailto:ealomar@stevens.edu)

Stevens Institute of Technology

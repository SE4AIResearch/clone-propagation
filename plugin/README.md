# CloneGuard — IntelliJ IDEA Plugin

Detects all 4 types of code clones from JetBrains AI inline suggestions
**BEFORE** the developer accepts them (before Tab is pressed).

---

## Project Structure

```
CloneGuard-IntelliJ/
├── build.gradle                          ← Gradle build (IntelliJ Plugin SDK)
├── settings.gradle
├── gradle.properties
└── src/main/
    ├── java/com/cloneguard/
    │   ├── model/
    │   │   ├── CloneType.java            ← Enum: TYPE_1 … TYPE_4
    │   │   ├── CloneResult.java          ← Detection result
    │   │   └── CloneGroup.java           ← Group of clones (Scenario 2)
    │   ├── detection/
    │   │   └── LocalCloneDetector.java   ← Layer 1: Karp-Rabin + normalization
    │   ├── services/
    │   │   ├── CloneIndexService.java    ← Manages index + runs pipeline
    │   │   ├── FileScannerService.java   ← Scenario 2: full file scan
    │   │   └── PythonServerClient.java   ← Layer 2: HTTP → localhost:8765
    │   ├── listeners/
    │   │   └── InlineSuggestionListener.java  ← CORE: intercepts ghost text
    │   └── ui/
    │       ├── CloneWarningDialog.java          ← Warning popup (Scenario 1)
    │       ├── CloneGuardToolWindowFactory.java ← Results panel (Scenario 2)
    │       ├── ScanFileAction.java              ← Tools menu action
    │       └── IndexFileAction.java             ← Manual index action
    └── resources/META-INF/
        └── plugin.xml                    ← Plugin registration
```

---

## Prerequisites

1. **IntelliJ IDEA** (Community or Ultimate) — 2023.x or 2024.x
2. **Java 17+** — IntelliJ's bundled JDK works fine
3. **Gradle** — bundled with IntelliJ
4. **Python server** running at localhost:8765 (for Type 3 & 4)

---

## Setup Steps

### Step 1 — Copy the project to your Mac

```bash
# Copy CloneGuard-IntelliJ folder to your Desktop
cp -r CloneGuard-IntelliJ ~/Desktop/
```

### Step 2 — Open in IntelliJ as a Gradle project

1. Open IntelliJ IDEA
2. File → Open → select the `CloneGuard-IntelliJ` folder
3. When prompted, click **"Open as Project"**
4. Trust the project
5. Wait for Gradle sync to complete (it downloads the IntelliJ Plugin SDK — ~500MB first time)

### Step 3 — Check your IntelliJ version

Open IntelliJ → Help → About → note the version number.
If your version is **2024.x**, update `build.gradle`:
```groovy
intellij {
    version = '2024.1.7'   // ← change to match your version
}
```

### Step 4 — Start the Python server

```bash
cd ~/Desktop/CloneGuard
source venv/bin/activate
export KMP_DUPLICATE_LIB_OK=TRUE
python server.py
```

Verify it's running: `curl http://localhost:8765/health`

### Step 5 — Run the plugin in sandbox IDE

In IntelliJ terminal:
```bash
./gradlew runIde
```

This opens a second IntelliJ window (the "sandbox IDE") with CloneGuard installed.

---

## How to Test

### Scenario 1 — Ghost Text Interception

1. In the sandbox IDE, open any Java file
2. Write a function, e.g.:
   ```java
   public int sum(int[] arr) {
       int total = 0;
       for (int x : arr) total += x;
       return total;
   }
   ```
3. Start typing a clone of it — JetBrains AI will suggest completion
4. **Before pressing Tab**, CloneGuard fires a warning popup
5. Choose: **Use Existing Function / Accept Anyway / Dismiss**

### Scenario 2 — Full File Scan

1. In the sandbox IDE, open a Java file with multiple similar methods
2. Press **Ctrl+Shift+G** (or Tools → CloneGuard → Scan Current File)
3. The CloneGuard panel opens at the bottom showing all clone groups
4. Click **Refactor →** on any group to see the refactoring suggestion

---

## Detection Logic

### Layer 1 (Local — no server needed)
| Clone Type | Method |
|-----------|--------|
| Type 1 — Exact | Karp-Rabin hash on whitespace-normalized code |
| Type 2 — Renamed | Karp-Rabin hash after replacing identifiers with VAR/FUNC |

### Layer 2 (Python Server — localhost:8765)
| Clone Type | Method |
|-----------|--------|
| Type 3 — Near-Miss | CodeBERT embeddings + FAISS cosine similarity |
| Type 4 — Semantic | CodeBERT embeddings + FAISS cosine similarity |

Layer 1 runs first (instant, no network). Layer 2 only runs if Layer 1 finds nothing.

---

## Build the plugin JAR (for distribution)

```bash
./gradlew buildPlugin
```

Output: `build/distributions/CloneGuard-1.0.0.zip`

Install in any IntelliJ: Settings → Plugins → ⚙ → Install Plugin from Disk

---

## Troubleshooting

**Gradle sync fails:** Make sure Java 17 is set. IntelliJ → File → Project Structure → SDK

**`runIde` opens but plugin not visible:** Check Help → About in sandbox IDE matches the version in build.gradle

**Ghost text not intercepted:** JetBrains AI must be enabled. Check Settings → Tools → AI Assistant

**Layer 2 not working:** Make sure Python server is running: `curl localhost:8765/health`

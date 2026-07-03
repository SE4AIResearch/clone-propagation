# CloneGuard — AI-Assisted Code Clone Detection & Refactoring

CloneGuard detects code clones in real time across three scenarios — paste
detection in IntelliJ, full-file scanning with one-click refactoring, and a
GitHub PR bot — and automatically refactors duplicated code using Method
Delegation.

## Structure

plugin/       — IntelliJ IDEA Java plugin (Gradle)
server/       — Python Flask server (CodeBERT + FAISS, Layer 2 detection)
extension/    — Chrome extension companion
test-repo/    — Test repo + GitHub Actions workflow (clone-check.yml) for Scenario 3



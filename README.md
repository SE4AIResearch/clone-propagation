# CloneGuard — AI-Assisted Code Clone Detection

CloneGuard detects code clones in real time across three scenarios: paste detection in IntelliJ, file scan, and GitHub PR agent.

## Structure

- plugin/ — IntelliJ IDEA Java plugin (Gradle)
- server/ — Python Flask server (CodeBERT + FAISS)
- test-repo/ — Test repo with GitHub Actions workflow

## Running the Server

cd server
pip install -r requirements.txt
KMP_DUPLICATE_LIB_OK=TRUE python3 server.py

## Clone Types

- Type 1 — Exact Clone (Layer 1)
- Type 2 — Renamed Clone (Layer 1)
- Type 3 — Near-Miss Clone (Layer 2)
- Type 4 — Semantic Clone (Layer 2)

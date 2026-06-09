'use strict';

let warningVisible = false;
let lastSuggestion = '';
let lastCode = '';

// ─────────────────────────────────────────
// GET ALL CODE FROM EDITOR
// Works on github.com and github.dev
// ─────────────────────────────────────────
function getExistingCode() {
  let code = '';

  // Method 1 — github.dev Monaco editor
  const monacoLines = document.querySelectorAll('.view-line');
  if (monacoLines.length > 0) {
    monacoLines.forEach(line => {
      code += (line.textContent || '') + '\n';
    });
  }

  // Method 2 — github.com CodeMirror editor
  if (!code.trim()) {
    const cmContent = document.querySelector('.cm-content');
    if (cmContent) {
      const lines = cmContent.querySelectorAll('.cm-line');
      lines.forEach(line => {
        const ghost = line.querySelector('.cm-ghostText');
        if (ghost) {
          const clone = line.cloneNode(true);
          clone.querySelectorAll('.cm-ghostText').forEach(g => g.remove());
          code += clone.textContent + '\n';
        } else {
          code += line.textContent + '\n';
        }
      });
    }
  }

  // Method 3 — textarea fallback
  if (!code.trim()) {
    const ta = document.querySelector('textarea.inputarea');
    if (ta) code = ta.value || '';
  }

  console.log('[CloneGuard] Code extracted, length:', code.trim().length);
  return code.trim();
}

// ─────────────────────────────────────────
// BUILD INDEX
// Send existing code to background.js
// ─────────────────────────────────────────
function buildIndex() {
  if (!chrome.runtime?.id) return;

  const code = getExistingCode();
  if (!code || code.length < 20) {
    console.log('[CloneGuard] Not enough code on page yet');
    return;
  }

  try {
    chrome.runtime.sendMessage(
      { type: 'BUILD_INDEX', code: code },
      (response) => {
        if (chrome.runtime.lastError) return;
        if (response && response.ok) {
          console.log('[CloneGuard] Index built:', response.count, 'functions');
        }
      }
    );
  } catch(e) {
    console.log('[CloneGuard] Context error:', e.message);
  }
}

// ─────────────────────────────────────────
// GET COPILOT GHOST TEXT
// ─────────────────────────────────────────
function getGhostText() {
  let text = '';

  // github.com ghost text
  const selectors = [
    '.cm-ghostText',
    '.copilot-ghost-text',
    '[data-copilot-suggestion]'
  ];

  for (const sel of selectors) {
    const els = document.querySelectorAll(sel);
    if (els.length > 0) {
      els.forEach(el => { text += el.textContent || ''; });
      if (text.trim()) break;
    }
  }

  // github.dev ghost text (Monaco inline suggestion)
  if (!text.trim()) {
    const ghostLines = document.querySelectorAll(
      '.ghost-text-decoration, .suggest-preview-text, ' +
      '[class*="ghost"], .inline-completion-text'
    );
    ghostLines.forEach(el => { text += el.textContent || ''; });
  }

  return text.trim();
}

// ─────────────────────────────────────────
// CHECK IF TEXT LOOKS LIKE CODE
// ─────────────────────────────────────────
function looksLikeCode(text) {
  return /function\s+\w+|const\s+\w+\s*=|=>\s*{|return\s+\w+|\w+\s*\([^)]*\)\s*{/.test(text);
}

// ─────────────────────────────────────────
// SEND CODE FOR CLONE CHECK
// ─────────────────────────────────────────
function checkSuggestion(code) {
  if (!chrome.runtime?.id) return;
  if (!code || code.length < 20) return;

  console.log('[CloneGuard] Checking for clone...');

  try {
    chrome.runtime.sendMessage(
      { type: 'CHECK_CLONE', code: code },
      (response) => {
        if (chrome.runtime.lastError) return;
        if (response && response.isClone) {
          console.log('[CloneGuard] Clone found!', response.cloneType);
          showWarning(response);
        } else {
          console.log('[CloneGuard] No clone detected');
        }
      }
    );
  } catch(e) {
    console.log('[CloneGuard] Check error:', e.message);
  }
}

// ─────────────────────────────────────────
// GET NEWLY TYPED CODE
// Compares old vs new to find what changed
// ─────────────────────────────────────────
function getNewlyTypedCode(oldCode, newCode) {
  if (!oldCode || !newCode) return '';
  if (newCode.length <= oldCode.length) return '';

  // Find the new lines added at the bottom
  const oldLines = oldCode.split('\n');
  const newLines = newCode.split('\n');

  // Get lines that are new
  const newPart = newLines.slice(oldLines.length).join('\n').trim();
  if (newPart.length > 20) return newPart;

  // Also check if a line was modified
  let changed = '';
  for (let i = 0; i < newLines.length; i++) {
    if (!oldLines[i] || newLines[i] !== oldLines[i]) {
      changed += newLines[i] + '\n';
    }
  }
  return changed.trim();
}

// ─────────────────────────────────────────
// MUTATIONOBSERVER
// Watches for ghost text AND typing
// ─────────────────────────────────────────
function startObserver() {
  let rebuildTimer = null;
  let checkTimer = null;

  const observer = new MutationObserver(() => {

    // ── Priority 1: Check for Copilot ghost text ──
    const ghost = getGhostText();
    if (ghost && ghost !== lastSuggestion && looksLikeCode(ghost)) {
      lastSuggestion = ghost;
      console.log('[CloneGuard] Ghost text detected!');
      buildIndex();
      setTimeout(() => checkSuggestion(ghost), 300);
      return;
    }

    // ── Priority 2: Check newly typed code ──
    clearTimeout(checkTimer);
    checkTimer = setTimeout(() => {
      const currentCode = getExistingCode();
      if (!currentCode || currentCode === lastCode) return;

      const newPart = getNewlyTypedCode(lastCode, currentCode);
      lastCode = currentCode;

      // Rebuild index with updated code
      buildIndex();

      // Check if newly typed part is a clone
      if (newPart && newPart.length > 30 && looksLikeCode(newPart)) {
        console.log('[CloneGuard] New code typed, checking:', newPart.slice(0, 50));
        setTimeout(() => checkSuggestion(newPart), 600);
      }
    }, 1000); // 1 second after typing stops

    // ── Rebuild index every 3 seconds max ──
    clearTimeout(rebuildTimer);
    rebuildTimer = setTimeout(buildIndex, 3000);
  });

  observer.observe(document.body, {
    childList: true,
    subtree: true,
    characterData: true
  });

  console.log('[CloneGuard] Observer started on', window.location.hostname);
}

// ─────────────────────────────────────────
// SHOW WARNING POPUP
// ─────────────────────────────────────────
function showWarning(result) {
  if (warningVisible) return;
  warningVisible = true;

  const old = document.getElementById('cloneguard-warning');
  if (old) old.remove();

  const sc = result.severity === 'High' ? 'cg-high'
    : result.severity === 'Medium' ? 'cg-medium' : 'cg-low';

  const popup = document.createElement('div');
  popup.id = 'cloneguard-warning';
  popup.innerHTML = `
    <div class="cg-header">⚠️ Clone Detected!</div>
    <div class="cg-body">
      <div class="cg-row">
        <span class="cg-label">Type</span>
        <span class="cg-value">${result.cloneType}</span>
      </div>
      <div class="cg-row">
        <span class="cg-label">Similarity</span>
        <span class="cg-value ${sc}">${result.similarity}</span>
      </div>
      <div class="cg-row">
        <span class="cg-label">Severity</span>
        <span class="cg-value ${sc}">${result.severity}</span>
      </div>
      <div class="cg-row">
        <span class="cg-label">Found In</span>
        <span class="cg-value">${result.matchFile}</span>
      </div>
      <div class="cg-row">
        <span class="cg-label">Line</span>
        <span class="cg-value">${result.matchLine}</span>
      </div>
      <div class="cg-row">
        <span class="cg-label">Function</span>
        <span class="cg-value">${result.matchFunction}</span>
      </div>
      <div class="cg-recommendation">
        💡 ${result.recommendation}
      </div>
    </div>
    <div class="cg-buttons">
      <button class="cg-btn cg-btn-primary" id="cg-use">Use Existing</button>
      <button class="cg-btn cg-btn-secondary" id="cg-dismiss">Dismiss</button>
    </div>
  `;

  document.body.appendChild(popup);

  document.getElementById('cg-use').addEventListener('click', () => {
    removeWarning();
    chrome.runtime.sendMessage({ type: 'CLONE_PREVENTED' });
    showToast('✅ Reuse the existing function instead');
  });

  document.getElementById('cg-dismiss').addEventListener('click', () => {
    removeWarning();
    chrome.runtime.sendMessage({ type: 'CLONE_DISMISSED' });
  });

  setTimeout(removeWarning, 15000);
}

// ─────────────────────────────────────────
// REMOVE WARNING
// ─────────────────────────────────────────
function removeWarning() {
  const p = document.getElementById('cloneguard-warning');
  if (p) p.remove();
  warningVisible = false;
  lastSuggestion = '';
}

// ─────────────────────────────────────────
// TOAST
// ─────────────────────────────────────────
function showToast(msg) {
  const t = document.createElement('div');
  t.style.cssText = `
    position:fixed;bottom:30px;right:20px;
    background:#40a02b;color:white;
    padding:10px 16px;border-radius:8px;
    font-family:Arial,sans-serif;font-size:13px;
    font-weight:bold;z-index:999999;
    box-shadow:0 4px 12px rgba(0,0,0,0.3);
  `;
  t.textContent = msg;
  document.body.appendChild(t);
  setTimeout(() => t.remove(), 3000);
}

// ─────────────────────────────────────────
// START
// ─────────────────────────────────────────
setTimeout(() => {
  buildIndex();
  startObserver();
  console.log('[CloneGuard] Started on', window.location.hostname);
}, 2000);
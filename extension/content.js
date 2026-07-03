'use strict';

let warningVisible = false;
let lastSuggestion = '';
let lastCode = '';
let indexSnapshot = '';

// ─────────────────────────────────────────
// HELPERS
// ─────────────────────────────────────────
function chunkIntoFunctions(code) {
  const chunks = [];
  let depth = 0, start = 0, inFunction = false;

  for (let i = 0; i < code.length; i++) {
    if (code[i] === '{') {
      depth++;
      if (depth === 1) inFunction = true;
    } else if (code[i] === '}') {
      depth--;
      if (depth === 0 && inFunction) {
        const chunk = code.slice(start, i + 1).trim();
        if (chunk.length > 20) chunks.push(chunk);
        start = i + 1;
        inFunction = false;
      }
    }
  }

  if (chunks.length === 0 && code.trim().length > 20) chunks.push(code.trim());
  return chunks;
}

function normalizeBody(code) {
  return code.replace(/\s+/g, ' ').trim();
}

function looksLikeCode(text) {
  return /function\s+\w+|const\s+\w+\s*=|let\s+\w+\s*=|=>\s*\{|return\s+\w+|\w+\s*\([^)]*\)\s*\{/.test(text);
}

const BENIGN_RUNTIME_ERRORS = [
  'The message port closed before a response was received.',
  'Could not establish connection. Receiving end does not exist.',
  'Extension context invalidated.'
];

function logRuntimeError(context) {
  if (!chrome.runtime.lastError) return;
  const msg = chrome.runtime.lastError.message || '';
  if (BENIGN_RUNTIME_ERRORS.some(e => msg.includes(e.replace(/\.$/, '')))) return;
  console.warn('[CloneGuard]', context, msg);
}

function sendMessageAsync(message, retries = 1) {
  return new Promise((resolve) => {
    if (!chrome.runtime?.id) {
      resolve(null);
      return;
    }
    try {
      chrome.runtime.sendMessage(message, (response) => {
        if (chrome.runtime.lastError) {
          const msg = chrome.runtime.lastError.message || '';
          const benign = BENIGN_RUNTIME_ERRORS.some(e => msg.includes(e.replace(/\.$/, '')));
          if (benign && retries > 0) {
            setTimeout(() => {
              sendMessageAsync(message, retries - 1).then(resolve);
            }, 400);
            return;
          }
          if (!benign) logRuntimeError(message.type || 'sendMessage');
          resolve(null);
          return;
        }
        resolve(response);
      });
    } catch (e) {
      resolve(null);
    }
  });
}

// ─────────────────────────────────────────
// GET ALL CODE FROM EDITOR
// ─────────────────────────────────────────
function getExistingCode() {
  let code = '';

  try {
    const models = window?.monaco?.editor?.getModels?.();
    if (models && models.length > 0) {
      code = models.map(m => m.getValue()).join('\n');
      if (code.trim()) return code.trim();
    }
  } catch (e) {}

  const viewLines = document.querySelector('.view-lines');
  if (viewLines) {
    viewLines.querySelectorAll('.view-line').forEach(line => {
      code += (line.textContent || '') + '\n';
    });
  }

  if (!code.trim()) {
    document.querySelectorAll('.view-line').forEach(line => {
      code += (line.textContent || '') + '\n';
    });
  }

  if (!code.trim()) {
    const cmContent = document.querySelector('.cm-content');
    if (cmContent) {
      cmContent.querySelectorAll('.cm-line').forEach(line => {
        const clone = line.cloneNode(true);
        clone.querySelectorAll('.cm-ghostText, .cm-inlineSuggestion').forEach(g => g.remove());
        code += clone.textContent + '\n';
      });
    }
  }

  if (!code.trim()) {
    const editor = document.querySelector('#editor, .js-file-line-container, [data-testid="codemirror-editor"]');
    if (editor) code = editor.innerText || editor.textContent || '';
  }

  if (!code.trim()) {
    const ta = document.querySelector('textarea.inputarea, textarea#code-editor');
    if (ta) code = ta.value || '';
  }

  return code.trim();
}

// ─────────────────────────────────────────
// BUILD INDEX — sends snapshot to background
// ─────────────────────────────────────────
function buildIndex() {
  if (!chrome.runtime?.id) return;
  if (!indexSnapshot || indexSnapshot.length < 20) return;

  sendMessageAsync({ type: 'BUILD_INDEX', code: indexSnapshot }).then((response) => {
    if (response && response.ok) {
      console.log('[CloneGuard] Index built:', response.count, 'functions');
    }
  });
}

function wakeServiceWorker() {
  sendMessageAsync({ type: 'PING' }).then((response) => {
    if (response && response.indexSize === 0 && indexSnapshot.length > 20) {
      console.log('[CloneGuard] Service worker awake — rebuilding index');
      buildIndex();
    }
  });
}

// ─────────────────────────────────────────
// CHECK SUGGESTION
// ─────────────────────────────────────────
function checkSuggestion(code, onComplete) {
  if (!chrome.runtime?.id) return;
  if (!code || code.length < 20) return;

  console.log('[CloneGuard] Checking...', code.slice(0, 60) + '...');

  sendMessageAsync({ type: 'CHECK_CLONE', code }).then((response) => {
    if (response && response.isClone) {
      console.log('[CloneGuard] Clone found!', response.cloneType);
      showWarning(response);
    } else {
      console.log('[CloneGuard] No clone');
      if (onComplete) onComplete();
    }
  });
}

// ─────────────────────────────────────────
// EXTRACT NEW / DUPLICATE CODE
// ─────────────────────────────────────────
function getCodeToCheck(currentCode) {
  if (!indexSnapshot || !currentCode) return '';

  const snapChunks = chunkIntoFunctions(indexSnapshot);
  const currChunks = chunkIntoFunctions(currentCode);

  const snapBodyCounts = new Map();
  for (const chunk of snapChunks) {
    const body = normalizeBody(chunk);
    snapBodyCounts.set(body, (snapBodyCounts.get(body) || 0) + 1);
  }

  const currBodyCounts = new Map();
  for (const chunk of currChunks) {
    const body = normalizeBody(chunk);
    currBodyCounts.set(body, (currBodyCounts.get(body) || 0) + 1);
  }

  // Type 1: exact duplicate — same body appears more times than in snapshot
  for (const [body, count] of currBodyCounts) {
    const snapCount = snapBodyCounts.get(body) || 0;
    if (body.length > 20 && count > snapCount) {
      const match = currChunks.find(c => normalizeBody(c) === body);
      console.log('[CloneGuard] Exact duplicate function body detected');
      return match;
    }
  }

  // New function added — compare only the new block(s), not edits to existing code
  if (currChunks.length > snapChunks.length) {
    for (let i = snapChunks.length; i < currChunks.length; i++) {
      const newChunk = currChunks[i];
      if (newChunk && newChunk.length > 20) {
        console.log('[CloneGuard] New function block detected');
        return newChunk;
      }
    }
  }

  return '';
}

function updateSnapshotAfterCleanEdit(currentCode) {
  indexSnapshot = currentCode;
  buildIndex();
}

// ─────────────────────────────────────────
// GHOST TEXT
// ─────────────────────────────────────────
function collectGhostText() {
  const selectors = [
    '.cm-ghostText',
    '.cm-inlineSuggestion',
    '.copilot-ghost-text',
    '.monaco-ghost-text',
    '.ghost-text',
    '.inlineSuggestionsHints',
    '[class*="ghost"]',
    '[class*="Ghost"]',
    '[class*="suggestion"]'
  ];

  let ghostText = '';
  for (const sel of selectors) {
    document.querySelectorAll(sel).forEach(el => {
      const text = (el.textContent || '').trim();
      if (text && text.length > ghostText.length) ghostText = text;
    });
  }
  return ghostText.trim();
}

// ─────────────────────────────────────────
// POLL FOR CODE
// ─────────────────────────────────────────
function startPolling() {
  let attempts = 0;
  const maxAttempts = 45;

  const poll = setInterval(() => {
    attempts++;
    const code = getExistingCode();

    if (code && code.length > 20) {
      console.log('[CloneGuard] Code found — building index');
      indexSnapshot = code;
      lastCode = code;
      buildIndex();
      clearInterval(poll);
      startObserver();
    } else if (attempts >= maxAttempts) {
      console.warn('[CloneGuard] Editor not found after polling — observer still active');
      clearInterval(poll);
      startObserver();
    } else {
      console.log('[CloneGuard] Attempt', attempts, '— waiting for editor...');
    }
  }, 2000);
}

// ─────────────────────────────────────────
// OBSERVER
// ─────────────────────────────────────────
function startObserver() {
  let checkTimer = null;

  const observer = new MutationObserver(() => {
    const ghostText = collectGhostText();

    if (ghostText && ghostText !== lastSuggestion && looksLikeCode(ghostText)) {
      lastSuggestion = ghostText;
      console.log('[CloneGuard] Ghost text detected');
      setTimeout(() => checkSuggestion(ghostText), 300);
      return;
    }

    clearTimeout(checkTimer);
    checkTimer = setTimeout(() => {
      const currentCode = getExistingCode();
      if (!currentCode || currentCode === lastCode) return;

      const prevCode = lastCode;
      lastCode = currentCode;

      if (currentCode.length < prevCode.length * 0.85 &&
          currentCode.length <= indexSnapshot.length * 1.05) {
        console.log('[CloneGuard] Code reduced — resetting snapshot');
        updateSnapshotAfterCleanEdit(currentCode);
        return;
      }

      const codeToCheck = getCodeToCheck(currentCode);
      if (codeToCheck && codeToCheck.length > 20 && looksLikeCode(codeToCheck)) {
        console.log('[CloneGuard] Sending for check:', codeToCheck.slice(0, 50));
        setTimeout(() => {
          checkSuggestion(codeToCheck, () => {
            if (!warningVisible) {
              updateSnapshotAfterCleanEdit(getExistingCode());
            }
          });
        }, 600);
      } else if (currentCode.length > indexSnapshot.length * 1.1 &&
          chunkIntoFunctions(currentCode).length === chunkIntoFunctions(indexSnapshot).length) {
        updateSnapshotAfterCleanEdit(currentCode);
      }
    }, 1000);
  });

  observer.observe(document.body, {
    childList: true,
    subtree: true,
    characterData: true
  });

  setInterval(() => buildIndex(), 60000);
  console.log('[CloneGuard] Observer ready');
}

// ─────────────────────────────────────────
// SHOW WARNING
// ─────────────────────────────────────────
function showWarning(result) {
  if (warningVisible) return;
  warningVisible = true;

  const old = document.getElementById('cloneguard-warning');
  if (old) old.remove();

  const sc = result.severity === 'High' ? 'cg-high'
    : result.severity === 'Medium' ? 'cg-medium' : 'cg-low';

  const layerLabel = result.layer === 2
    ? '<span class="cg-layer-badge layer2">Layer 2</span>'
    : '<span class="cg-layer-badge layer1">Layer 1</span>';

  const popup = document.createElement('div');
  popup.id = 'cloneguard-warning';
  popup.innerHTML = `
    <div class="cg-header">⚠️ Clone Detected! ${layerLabel}</div>
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
    updateSnapshotAfterCleanEdit(getExistingCode());
  });

  document.getElementById('cg-dismiss').addEventListener('click', () => {
    removeWarning();
    chrome.runtime.sendMessage({ type: 'CLONE_DISMISSED' });
    updateSnapshotAfterCleanEdit(getExistingCode());
  });

  setTimeout(removeWarning, 15000);
}

function removeWarning() {
  const p = document.getElementById('cloneguard-warning');
  if (p) p.remove();
  warningVisible = false;
  lastSuggestion = '';
}

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
  console.log('[CloneGuard] Starting on', window.location.hostname);
  wakeServiceWorker();
  startPolling();
}, 1500);

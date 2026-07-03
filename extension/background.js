'use strict';

const CLONEGUARD_SERVER = 'http://127.0.0.1:8765';

let liveIndex = {};
let indexedFunctions = [];
let lastIndexedCode = '';
let sessionStats = {
  cloneFound: 0,
  warningsShown: 0,
  clonesPrevented: 0,
  clonesDismissed: 0
};

const JS_KEYWORDS = new Set([
  'function', 'return', 'const', 'let', 'var',
  'if', 'else', 'for', 'while', 'do', 'switch',
  'case', 'break', 'continue', 'new', 'delete',
  'typeof', 'instanceof', 'in', 'of', 'class',
  'extends', 'super', 'this', 'import', 'export',
  'default', 'async', 'await', 'try', 'catch',
  'finally', 'throw', 'null', 'undefined', 'true',
  'false', 'void', '=>', '{', '}', '(', ')', '[',
  ']', ';', ',', '.', '=', '+', '-', '*', '/',
  '!', '&', '|', '>', '<', 'STR', 'NUM'
]);

function tokenize(code) {
  code = code.replace(/\/\/[^\n]*/g, ' ');
  code = code.replace(/\/\*[\s\S]*?\*\//g, ' ');
  code = code.replace(/'[^']*'/g, 'STR');
  code = code.replace(/"[^"]*"/g, 'STR');
  code = code.replace(/`[^`]*`/g, 'STR');
  code = code.replace(/\b\d+\.?\d*\b/g, 'NUM');
  const raw = code.split(/([{}()\[\];,.\s=>+\-*\/!&|])/);
  return raw
    .map(t => t.trim())
    .filter(t => t.length > 0)
    .map(t => normalizeToken(t));
}

function normalizeToken(token) {
  if (JS_KEYWORDS.has(token)) return token;
  if (/^[a-zA-Z_$][a-zA-Z0-9_$]*$/.test(token)) return 'VAR';
  return token;
}

function karpRabinHashes(tokens, windowSize) {
  const hashes = [];
  const p1 = 31, p2 = 37, M = 1e9 + 7;

  if (tokens.length < windowSize) {
    if (tokens.length >= 4) {
      const str = tokens.join('|');
      let h1 = 0, h2 = 0;
      for (let j = 0; j < str.length; j++) {
        h1 = (h1 * p1 + str.charCodeAt(j)) % M;
        h2 = (h2 * p2 + str.charCodeAt(j)) % M;
      }
      hashes.push(`${h1}_${h2}`);
    }
    return hashes;
  }

  for (let i = 0; i <= tokens.length - windowSize; i++) {
    const windowStr = tokens.slice(i, i + windowSize).join('|');
    let h1 = 0, h2 = 0;
    for (let j = 0; j < windowStr.length; j++) {
      h1 = (h1 * p1 + windowStr.charCodeAt(j)) % M;
      h2 = (h2 * p2 + windowStr.charCodeAt(j)) % M;
    }
    hashes.push(`${h1}_${h2}`);
  }
  return hashes;
}

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

function tokenizeRaw(code) {
  code = code.replace(/\/\/[^\n]*/g, ' ');
  code = code.replace(/\/\*[\s\S]*?\*\//g, ' ');
  code = code.replace(/'[^']*'/g, 'STR');
  code = code.replace(/"[^"]*"/g, 'STR');
  code = code.replace(/`[^`]*`/g, 'STR');
  code = code.replace(/\b\d+\.?\d*\b/g, 'NUM');
  const raw = code.split(/([{}()\[\];,.\s=>+\-*\/!&|])/);
  return raw.map(t => t.trim()).filter(t => t.length > 0);
}

function tokenSimilarity(tokensA, tokensB) {
  const minLen = Math.min(tokensA.length, tokensB.length);
  const maxLen = Math.max(tokensA.length, tokensB.length);
  if (maxLen === 0) return 0;
  let matchCount = 0;
  for (let i = 0; i < minLen; i++) {
    if (tokensA[i] === tokensB[i]) matchCount++;
  }
  return Math.round((matchCount / maxLen) * 100);
}

function persistIndex() {
  chrome.storage.session.set({
    indexedFunctions,
    lastIndexedCode,
    sessionStats
  }).catch(() => {});
}

function restoreIndex() {
  chrome.storage.session.get(['indexedFunctions', 'lastIndexedCode', 'sessionStats'], (data) => {
    if (chrome.runtime.lastError) return;
    if (data.indexedFunctions && data.indexedFunctions.length > 0) {
      indexedFunctions = data.indexedFunctions;
      rebuildHashIndex();
      lastIndexedCode = data.lastIndexedCode || '';
      console.log('[CloneGuard] Restored', indexedFunctions.length, 'functions from session');
    }
    if (data.sessionStats) {
      sessionStats = { ...sessionStats, ...data.sessionStats };
    }
  });
}

function rebuildHashIndex() {
  liveIndex = {};
  indexedFunctions.forEach((entry) => {
    const hashes = karpRabinHashes(entry.tokens, 8);
    hashes.forEach(hash => {
      if (!liveIndex[hash]) liveIndex[hash] = entry;
    });
  });
}

restoreIndex();

function estimateLine(fullCode, chunk) {
  const idx = fullCode.indexOf(chunk.slice(0, 30));
  if (idx === -1) return 1;
  return fullCode.slice(0, idx).split('\n').length;
}

function buildLiveIndex(existingCode) {
  liveIndex = {};
  indexedFunctions = [];
  if (!existingCode || existingCode.trim().length === 0) return 0;

  lastIndexedCode = existingCode;
  const chunks = chunkIntoFunctions(existingCode);

  chunks.forEach((chunk, i) => {
    const tokens = tokenize(chunk);
    if (tokens.length < 4) return;

    const nameMatch = chunk.match(/function\s+(\w+)|const\s+(\w+)\s*=|(\w+)\s*\(/);
    const funcName = nameMatch
      ? (nameMatch[1] || nameMatch[2] || nameMatch[3] || `function_${i}`)
      : `function_${i}`;

    const entry = {
      name: funcName,
      tokens,
      snippet: chunk,
      line: estimateLine(existingCode, chunk)
    };

    indexedFunctions.push(entry);
    karpRabinHashes(tokens, 8).forEach(hash => {
      if (!liveIndex[hash]) liveIndex[hash] = entry;
    });
  });

  console.log(`[CloneGuard] Live index built: ${indexedFunctions.length} functions, ${Object.keys(liveIndex).length} hashes`);
  persistIndex();
  sendToLayer2Index(existingCode);
  return indexedFunctions.length;
}

function hasMeaningfulBody(chunk) {
  const inner = chunk.replace(/^[^{]*\{/, '').replace(/\}\s*$/, '').trim();
  const tokens = tokenize(chunk);
  return tokens.length >= 8 && inner.length >= 8;
}

function classifyLayer1CloneType(suggestionChunk, storedSnippet, varPercent) {
  if (normalizeBody(suggestionChunk) === normalizeBody(storedSnippet)) {
    return 'Type 1 — Exact Clone';
  }
  if (varPercent >= 97) {
    return 'Type 2 — Renamed Clone';
  }
  return null;
}

function findBestLayer1Match(suggestionChunk) {
  if (!hasMeaningfulBody(suggestionChunk)) return null;

  const suggestionTokens = tokenize(suggestionChunk);
  const suggestionBody = normalizeBody(suggestionChunk);

  let best = null;

  for (const stored of indexedFunctions) {
    if (normalizeBody(stored.snippet) === suggestionBody) continue;
    if (!hasMeaningfulBody(stored.snippet)) continue;

    const varPercent = tokenSimilarity(suggestionTokens, stored.tokens);
    if (varPercent < 92) continue;

    const cloneType = classifyLayer1CloneType(suggestionChunk, stored.snippet, varPercent);
    if (!cloneType) continue;

    if (!best || varPercent > best.varPercent) {
      best = {
        stored,
        cloneType,
        varPercent,
        severity: varPercent >= 95 ? 'High' : 'Medium'
      };
    }
  }

  return best;
}

function runLayer1(suggestionCode) {
  if (indexedFunctions.length === 0) {
    console.log('[CloneGuard] Layer 1 skipped — index empty');
    return { isClone: false };
  }

  const chunks = chunkIntoFunctions(suggestionCode);

  for (const chunk of chunks) {
    const match = findBestLayer1Match(chunk);
    if (!match) continue;

    console.log('[CloneGuard] CLONE CONFIRMED Layer 1:', match.cloneType,
      `var=${match.varPercent}%`);

    return {
      isClone: true,
      cloneType: match.cloneType,
      similarity: match.varPercent + '%',
      severity: match.severity,
      matchFile: 'Current file',
      matchLine: match.stored.line,
      matchFunction: match.stored.name + '()',
      recommendation: `This is similar to ${match.stored.name}() already written above. Consider reusing it instead.`,
      layer: 1
    };
  }

  return { isClone: false };
}

async function sendToLayer2Index(existingCode) {
  try {
    const response = await fetch(`${CLONEGUARD_SERVER}/index`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: existingCode })
    });
    if (response.ok) {
      const data = await response.json();
      console.log('[CloneGuard] Layer 2 FAISS index built:', data.indexed, 'vectors');
    }
  } catch (e) {
    console.log('[CloneGuard] Layer 2 server not running — Layer 1 only mode');
  }
}

async function runLayer2(suggestionCode) {
  try {
    const response = await fetch(`${CLONEGUARD_SERVER}/check`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ suggestion: suggestionCode })
    });
    if (!response.ok) return { isClone: false };

    const result = await response.json();
    if (result.isClone) {
      result.layer = 2;
      console.log('[CloneGuard] Layer 2 clone found:', result.cloneType, result.similarity);
    }
    return result;
  } catch (e) {
    console.log('[CloneGuard] Layer 2 server offline — skipping semantic check');
    return { isClone: false };
  }
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {

  if (message.type === 'PING') {
    sendResponse({
      ok: true,
      indexSize: indexedFunctions.length,
      hasIndexedCode: lastIndexedCode.length > 20
    });
    return true;
  }

  if (message.type === 'BUILD_INDEX') {
    const count = buildLiveIndex(message.code);
    sendResponse({ ok: true, count });
    return true;
  }

  if (message.type === 'CHECK_CLONE') {
    if (indexedFunctions.length === 0 && lastIndexedCode.length > 20) {
      buildLiveIndex(lastIndexedCode);
    }

    const layer1Result = runLayer1(message.code);

    if (layer1Result.isClone) {
      sessionStats.cloneFound++;
      sessionStats.warningsShown++;
      persistIndex();
      chrome.action.setBadgeText({ text: sessionStats.cloneFound.toString() });
      chrome.action.setBadgeBackgroundColor({ color: '#e24b4b' });
      sendResponse(layer1Result);
      return true;
    }

    console.log('[CloneGuard] Layer 1 clear — running Layer 2 (CodeBERT)...');
    runLayer2(message.code).then(layer2Result => {
      if (layer2Result.isClone) {
        sessionStats.cloneFound++;
        sessionStats.warningsShown++;
        persistIndex();
        chrome.action.setBadgeText({ text: sessionStats.cloneFound.toString() });
        chrome.action.setBadgeBackgroundColor({ color: '#e24b4b' });
      }
      sendResponse(layer2Result);
    });
    return true;
  }

  if (message.type === 'CLONE_PREVENTED') {
    sessionStats.clonesPrevented++;
    persistIndex();
    sendResponse({ ok: true });
    return true;
  }

  if (message.type === 'CLONE_DISMISSED') {
    sessionStats.clonesDismissed++;
    persistIndex();
    sendResponse({ ok: true });
    return true;
  }

  if (message.type === 'GET_STATS') {
    sendResponse({ stats: sessionStats, indexSize: indexedFunctions.length });
    return true;
  }

  if (message.type === 'CHECK_LAYER2') {
    fetch(`${CLONEGUARD_SERVER}/health`)
      .then(res => res.json())
      .then(data => sendResponse({ online: true, indexed: data.indexed }))
      .catch(() => sendResponse({ online: false, indexed: 0 }));
    return true;
  }

});

console.log('[CloneGuard] Layer 1 + Layer 2 engine ready');

chrome.alarms.create('keepAlive', { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === 'keepAlive') {
    console.log('[CloneGuard] Service worker keepalive');
  }
});

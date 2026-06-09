// ─────────────────────────────────────────
// CloneGuard — background.js
// Layer 1: Real-time clone detection
// Builds live index from page code
// Checks every Copilot suggestion
// ─────────────────────────────────────────

'use strict';

// ─────────────────────────────────────────
// LIVE INDEX
// Built from the actual code on the page
// Rebuilt every time page code changes
// No hardcoded data
// ─────────────────────────────────────────
let liveIndex = {};
let sessionStats = {
  cloneFound: 0,
  warningsShown: 0,
  clonesPrevented: 0,
  clonesDismissed: 0
};

// ─────────────────────────────────────────
// JAVASCRIPT KEYWORDS
// These stay as-is during normalization
// Everything else becomes VAR
// ─────────────────────────────────────────
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

// ─────────────────────────────────────────
// STEP 1: TOKENIZE
// Breaks raw code into normalized tokens
// Removes comments, whitespace
// Replaces names with VAR
// Replaces numbers with NUM
// Replaces strings with STR
// ─────────────────────────────────────────
function tokenize(code) {
  // Remove single line comments
  code = code.replace(/\/\/[^\n]*/g, ' ');

  // Remove multi line comments
  code = code.replace(/\/\*[\s\S]*?\*\//g, ' ');

  // Replace string literals with STR
  code = code.replace(/'[^']*'/g, 'STR');
  code = code.replace(/"[^"]*"/g, 'STR');
  code = code.replace(/`[^`]*`/g, 'STR');

  // Replace numbers with NUM
  code = code.replace(/\b\d+\.?\d*\b/g, 'NUM');

  // Split on whitespace and punctuation
  const raw = code.split(/([{}()\[\];,.\s=>+\-*\/!&|])/);

  // Clean and normalize each token
  return raw
    .map(t => t.trim())
    .filter(t => t.length > 0)
    .map(t => normalizeToken(t));
}

// ─────────────────────────────────────────
// STEP 2: NORMALIZE TOKEN
// Keeps keywords as-is
// Replaces identifiers with VAR
// ─────────────────────────────────────────
function normalizeToken(token) {
  // Keep JavaScript keywords
  if (JS_KEYWORDS.has(token)) return token;

  // Replace identifiers with VAR
  if (/^[a-zA-Z_$][a-zA-Z0-9_$]*$/.test(token)) return 'VAR';

  return token;
}

// ─────────────────────────────────────────
// STEP 3: KARP-RABIN DOUBLE HASH
// Generates fingerprints for token windows
// Uses two prime bases for fewer collisions
// Window size = 8 tokens
// ─────────────────────────────────────────
function karpRabinHashes(tokens, windowSize) {
  const hashes = [];
  const p1 = 31;
  const p2 = 37;
  const M  = 1e9 + 7;

  // If function is shorter than window
  // Hash the whole thing
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

  // Slide window across token stream
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

// ─────────────────────────────────────────
// STEP 4: CHUNK INTO FUNCTIONS
// Splits large code blocks into individual
// functions using brace depth counting
// ─────────────────────────────────────────
function chunkIntoFunctions(code) {
  const chunks = [];
  let depth = 0;
  let start = 0;
  let inFunction = false;

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

  // If no function boundaries found
  // treat whole code as one chunk
  if (chunks.length === 0 && code.trim().length > 20) {
    chunks.push(code.trim());
  }

  return chunks;
}

// ─────────────────────────────────────────
// STEP 5: BUILD LIVE INDEX
// Called when content.js sends the
// existing code from the GitHub page
// Tokenizes every function and stores
// hashes in liveIndex
// ─────────────────────────────────────────
function buildLiveIndex(existingCode) {
  // Reset index every time
  liveIndex = {};

  if (!existingCode || existingCode.trim().length === 0) {
    console.log('[CloneGuard] No existing code to index');
    return 0;
  }

  // Split into individual functions
  const chunks = chunkIntoFunctions(existingCode);
  let indexedCount = 0;

  chunks.forEach((chunk, i) => {
    const tokens = tokenize(chunk);

    // Only index if enough tokens
    if (tokens.length < 4) return;

    const hashes = karpRabinHashes(tokens, 8);

    // Extract function name for the warning message
    const nameMatch = chunk.match(/function\s+(\w+)|const\s+(\w+)\s*=|(\w+)\s*\(/);
    const funcName = nameMatch
      ? (nameMatch[1] || nameMatch[2] || nameMatch[3] || `function_${i}`)
      : `function_${i}`;

    // Store each hash pointing to this function
    hashes.forEach(hash => {
      if (!liveIndex[hash]) {
        liveIndex[hash] = {
          name: funcName,
          tokens: tokens,
          snippet: chunk.slice(0, 80) + '...',
          line: estimateLine(existingCode, chunk)
        };
      }
    });

    indexedCount++;
  });

  console.log(`[CloneGuard] Live index built: ${indexedCount} functions, ${Object.keys(liveIndex).length} hashes`);
  return indexedCount;
}

// Estimate which line a chunk starts on
function estimateLine(fullCode, chunk) {
  const idx = fullCode.indexOf(chunk.slice(0, 30));
  if (idx === -1) return 1;
  return fullCode.slice(0, idx).split('\n').length;
}

// ─────────────────────────────────────────
// STEP 6: SEARCH LIVE INDEX
// Look up hashes in the live index
// Returns the matched function info
// ─────────────────────────────────────────
function searchIndex(hashes) {
  for (const hash of hashes) {
    if (liveIndex[hash]) {
      return liveIndex[hash];
    }
  }
  return null;
}

// ─────────────────────────────────────────
// STEP 7: VERIFY MATCH
// Confirms hash match is a real clone
// Not just a hash collision
// Compares actual token sequences
// ─────────────────────────────────────────
function verify(suggestionTokens, storedTokens) {
  if (!suggestionTokens.length || !storedTokens.length) return false;

  const minLen = Math.min(suggestionTokens.length, storedTokens.length);
  let matchCount = 0;

  for (let i = 0; i < minLen; i++) {
    if (suggestionTokens[i] === storedTokens[i]) matchCount++;
  }

  // 75% token match = confirmed clone
  return (matchCount / minLen) >= 0.75;
}

// ─────────────────────────────────────────
// STEP 8: CALCULATE SIMILARITY
// Returns percentage and severity level
// ─────────────────────────────────────────
function calculateSimilarity(suggestionTokens, storedTokens) {
  const minLen = Math.min(suggestionTokens.length, storedTokens.length);
  const maxLen = Math.max(suggestionTokens.length, storedTokens.length);
  let matchCount = 0;

  for (let i = 0; i < minLen; i++) {
    if (suggestionTokens[i] === storedTokens[i]) matchCount++;
  }

  const score  = matchCount / maxLen;
  const percent = Math.round(score * 100);
  const severity = percent >= 90 ? 'High'
    : percent >= 75 ? 'Medium'
    : 'Low';

  return { percent, severity };
}

// ─────────────────────────────────────────
// STEP 9: RUN LAYER 1
// Full detection pipeline
// Called when Copilot suggestion arrives
// ─────────────────────────────────────────
function runLayer1(suggestionCode) {

  // Check if index has anything
  if (Object.keys(liveIndex).length === 0) {
    console.log('[CloneGuard] Index empty — no existing code indexed yet');
    return { isClone: false };
  }

  // Chunk the suggestion into functions
  const chunks = chunkIntoFunctions(suggestionCode);
  console.log('[CloneGuard] Suggestion chunks:', chunks.length);

  for (const chunk of chunks) {

    // Tokenize and normalize
    const tokens = tokenize(chunk);
    console.log('[CloneGuard] Suggestion tokens:', tokens.length);

    // Skip if too short
    if (tokens.length < 4) continue;

    // Generate hashes
    const hashes = karpRabinHashes(tokens, 8);

    // Search live index
    const match = searchIndex(hashes);

    if (match) {
      console.log('[CloneGuard] Match found:', match.name);

      // Verify — not a collision
      const confirmed = verify(tokens, match.tokens);

      if (confirmed) {
        const { percent, severity } = calculateSimilarity(tokens, match.tokens);

        // Determine clone type
        // Type 1 = 95%+ match (near identical)
        // Type 2 = 75-94% match (renamed variables)
        const cloneType = percent >= 95
          ? 'Type 1 — Exact Clone'
          : 'Type 2 — Renamed Clone';

        console.log('[CloneGuard] CLONE CONFIRMED:', cloneType, percent + '%');

        return {
          isClone: true,
          cloneType: cloneType,
          similarity: percent + '%',
          severity: severity,
          matchFile: 'Current file',
          matchLine: match.line,
          matchFunction: match.name + '()',
          recommendation: `This is similar to ${match.name}() already written above. Consider reusing it instead.`
        };
      }
    }
  }

  return { isClone: false };
}

// ─────────────────────────────────────────
// CHROME MESSAGE LISTENER
// Handles all messages from content.js
// ─────────────────────────────────────────
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {

  // ── BUILD INDEX from existing page code ──
  if (message.type === 'BUILD_INDEX') {
    const count = buildLiveIndex(message.code);
    console.log('[CloneGuard] Index built with', count, 'functions');
    sendResponse({ ok: true, count: count });
    return true;
  }

  // ── CHECK CLONE on Copilot suggestion ──
  if (message.type === 'CHECK_CLONE') {
    console.log('[CloneGuard] Checking suggestion for clones...');
    const result = runLayer1(message.code);

    if (result.isClone) {
      sessionStats.cloneFound++;
      sessionStats.warningsShown++;
      chrome.action.setBadgeText({
        text: sessionStats.cloneFound.toString()
      });
      chrome.action.setBadgeBackgroundColor({ color: '#e24b4b' });
    }

    sendResponse(result);
    return true;
  }

  // ── CLONE PREVENTED ──
  if (message.type === 'CLONE_PREVENTED') {
    sessionStats.clonesPrevented++;
    sendResponse({ ok: true });
    return true;
  }

  // ── CLONE DISMISSED ──
  if (message.type === 'CLONE_DISMISSED') {
    sessionStats.clonesDismissed++;
    sendResponse({ ok: true });
    return true;
  }

  // ── GET STATS for popup dashboard ──
  if (message.type === 'GET_STATS') {
    sendResponse({
      stats: sessionStats,
      indexSize: Object.keys(liveIndex).length
    });
    return true;
  }
});

console.log('[CloneGuard] Layer 1 engine ready — waiting for page code');
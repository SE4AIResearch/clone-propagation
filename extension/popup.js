document.addEventListener('DOMContentLoaded', () => {

  // ── Get extension stats ──
  setTimeout(() => {
    chrome.runtime.sendMessage({ type: 'GET_STATS' }, (response) => {
      if (chrome.runtime.lastError) return;
      if (!response) return;

      const stats = response.stats;
      const indexSize = response.indexSize || 0;

      document.getElementById('stat-found').textContent     = stats.cloneFound      || 0;
      document.getElementById('stat-warnings').textContent  = stats.warningsShown   || 0;
      document.getElementById('stat-prevented').textContent = stats.clonesPrevented || 0;
      document.getElementById('stat-dismissed').textContent = stats.clonesDismissed || 0;

      const indexEl = document.getElementById('index-size');
      if (indexSize > 0) {
        indexEl.textContent = indexSize + ' hash entries in live index';
        indexEl.style.color = '#40a02b';
      } else {
        indexEl.textContent = 'Index building... click again in 3 seconds';
        indexEl.style.color = '#f9a825';
      }
    });
  }, 500);

  // ── Check if Layer 2 Python server is running ──
  // Routes through background.js because popup cannot fetch http:// directly
  const layer2El = document.getElementById('layer2-status');

  chrome.runtime.sendMessage({ type: 'CHECK_LAYER2' }, (response) => {
    if (chrome.runtime.lastError || !response) {
      layer2El.textContent = 'Offline — run: python server.py';
      layer2El.style.color = '#f9a825';
      return;
    }
    if (response.online) {
      layer2El.textContent = 'Online — ' + response.indexed + ' vectors indexed';
      layer2El.style.color = '#a6e3a1';
    } else {
      layer2El.textContent = 'Offline — run: python server.py';
      layer2El.style.color = '#f9a825';
    }
  });

});
document.addEventListener('DOMContentLoaded', () => {

  // Wait a moment then get stats
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
});
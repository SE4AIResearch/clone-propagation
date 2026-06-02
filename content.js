// CloneGuard - content.js
// This file watches the page for Copilot suggestions

console.log("CloneGuard is active!");

// This function shows the warning popup
function showWarning(cloneData) {

  // Remove existing warning if any
  const existing = document.getElementById("cloneguard-warning");
  if (existing) existing.remove();

  // Create the warning div
  const warning = document.createElement("div");
  warning.id = "cloneguard-warning";

  // Fill in the warning content
  warning.innerHTML = `
    <div class="cg-header">
      <div class="cg-icon">⚠️</div>
      <div class="cg-title">Clone Detected!</div>
      <div class="cg-close" id="cg-close-btn">✕</div>
    </div>

    <div class="cg-row">
      <span class="cg-label">Type</span>
      <span class="cg-value red">${cloneData.type}</span>
    </div>

    <div class="cg-row">
      <span class="cg-label">Similarity</span>
      <span class="cg-value red">${cloneData.similarity}</span>
    </div>

    <div class="cg-row">
      <span class="cg-label">Severity</span>
      <span class="cg-badge ${cloneData.severityClass}">${cloneData.severity}</span>
    </div>

    <div class="cg-match">
      📁 ${cloneData.matchFile}<br>
      🔍 ${cloneData.matchFunction}
    </div>

    <div class="cg-recommendation">
      💡 ${cloneData.recommendation}
    </div>

    <hr class="cg-divider"/>

    <div class="cg-buttons">
      <div class="cg-btn cg-btn-primary" id="cg-use-btn">Use Existing</div>
      <div class="cg-btn cg-btn-secondary" id="cg-dismiss-btn">Dismiss</div>
    </div>
  `;

  // Add to page
  document.body.appendChild(warning);

  // Close button
  document.getElementById("cg-close-btn").addEventListener("click", () => {
    warning.remove();
  });

  // Dismiss button
  document.getElementById("cg-dismiss-btn").addEventListener("click", () => {
    warning.remove();
  });

  // Use existing button
  document.getElementById("cg-use-btn").addEventListener("click", () => {
    alert(`Navigate to: ${cloneData.matchFile}`);
    warning.remove();
  });

  // Auto remove after 15 seconds
  setTimeout(() => {
    if (document.getElementById("cloneguard-warning")) {
      warning.remove();
    }
  }, 15000);
}

// Watch the page for any new code being typed
// For the demo - we simulate a clone detection
function watchForSuggestions() {
  // Listen for keyboard events
  document.addEventListener("keyup", function(event) {
    // Get any selected or typed text
    const activeEl = document.activeElement;
    
    if (activeEl && (activeEl.tagName === "TEXTAREA" || activeEl.isContentEditable)) {
      const text = activeEl.value || activeEl.innerText || "";
      
      // Check if code looks like a function
      if (text.includes("function") && text.includes("reduce")) {
        // Simulate clone detection result
        showWarning({
          type: "Type 2 — Renamed Clone",
          similarity: "94%",
          severity: "High",
          severityClass: "high",
          matchFile: "utils/calculator.js → line 34",
          matchFunction: "calculateSum(numbers)",
          recommendation: "Consider using the existing calculateSum() instead"
        });
      }
    }
  });
}

// Start watching
watchForSuggestions();
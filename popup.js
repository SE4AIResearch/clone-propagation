// CloneGuard - popup.js
// This handles the popup window logic

// When popup opens - load stats from storage
document.addEventListener("DOMContentLoaded", () => {

  // Get stats from Chrome storage
  chrome.storage.local.get(["clonesFound", "warningsShown"], (data) => {
    
    // Update the numbers on screen
    document.getElementById("clones-found").textContent = 
      data.clonesFound || 0;
    
    document.getElementById("warnings-shown").textContent = 
      data.warningsShown || 0;
  });

});
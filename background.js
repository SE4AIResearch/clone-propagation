// CloneGuard - background.js
// This runs in the background and handles messages

console.log("CloneGuard background worker running!");

// Listen for messages from content.js
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  
  if (message.type === "CHECK_CLONE") {
    
    // For now this is a demo response
    // Later this will call the Python backend
    const code = message.code;
    
    // Simulate clone detection
    if (code.includes("reduce") || code.includes("function")) {
      sendResponse({
        isClone: true,
        cloneData: {
          type: "Type 2 — Renamed Clone",
          similarity: "94%",
          severity: "High",
          severityClass: "high",
          matchFile: "utils/calculator.js → line 34",
          matchFunction: "calculateSum(numbers)",
          recommendation: "Consider using the existing calculateSum() instead"
        }
      });
    } else {
      sendResponse({
        isClone: false
      });
    }
  }

  // Required for async response
  return true;
});
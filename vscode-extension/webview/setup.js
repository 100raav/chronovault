/*!
 * CHRONOVAULT setup-page glue for the embedded dashboard.
 * Binds [data-cv-setup] buttons to postMessage so the extension can act
 * (Configure CLI / Locate Runtime / Retry / Open in Browser). No network here.
 */
(function () {
  "use strict";
  var vscode = acquireVsCodeApi();
  document.querySelectorAll("[data-cv-setup]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      try {
        vscode.postMessage({ type: "setup", action: btn.getAttribute("data-cv-setup") });
      } catch (e) { /* noop */ }
    });
  });
})();
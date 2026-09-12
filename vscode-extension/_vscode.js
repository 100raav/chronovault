"use strict";

/*
 * Resolve the VS Code host API when running inside an extension host, or fall
 * back to a minimal stub when the module is loaded by unit tests in plain Node.
 */
let vscode;
try {
  vscode = require("vscode");
} catch {
  vscode = require("./stubs/vscode.js");
}
module.exports = vscode;
# ChronoVault for VS Code

Snapshot, diagnose, and restore your project's verified states without leaving the
editor. The extension shells out to the `chronovault` CLI — the CLI stays the single
source of truth.

## Install

```bash
npm install -g @vscode/vsce    # or use a local install
cd vscode-extension
npx vsce package              # produce chronovault-1.0.0.vsix
code --install-extension chronovault-1.0.0.vsix
```

## Usage

Open your project folder, then run any command from the palette (`Cmd/Ctrl+Shift+P`):

- **ChronoVault: Create checkpoint** — verify + snapshot (uses the project's real
  build/test profile).
- **ChronoVault: Restore last good state** — restore to the last verified checkpoint,
  with automatic rollback protection.
- **ChronoVault: What broke it?** — opens a panel with the evidence cards.
- **ChronoVault: Show protection status** — vault summary.
- **ChronoVault: Open dashboard** — launches the local web dashboard.

A status-bar item shows ChronoVault presence; click it to view protection status.

## Configuration

- `chronovault.cliPath` — path to the CLI binary (default: `chronovault` on `PATH`).

The extension sets `CHRONOVAULT_PROJECT` to the opened workspace folder, so the vault
path always matches what you see in the editor.
# Troubleshooting

## Commands

### `chronovault: command not found`
- The CLI is not on `PATH`. Re-run the `export PATH=...` line (see
  [INSTALLATION.md](INSTALLATION.md)) and reload the shell.
- VS Code / IntelliJ launch their own shells; set `chronovault.cliPath` (VS Code)
  after running `which chronovault`.

### `Java version ... is too old`
CHRONOVAULT requires Java 21+. `java -version`; install Temurin 21 and make it the
default.

### `Error opening zip file` / `Could not find or load main class`
Corrupted or incomplete `installDist`. Delete `cli/build/install` and rebuild.

## Vault & recovery

### Restore said everything rolled back — is my work lost?
No. Rollback happens *to the exact state CHRONOVAULT protected right before the
restore*. The pre-restore snapshot you made (including your broken-but-saved work) is
still in the vault. Run `chronovault compare` or the dashboard to inspect it, then
`chronovault restore --to <id>` to bring it back.

### Restore left ignored files behind
That is by design: a strict restore never deletes files matching the ignore rules
(`.env`, `.git`, `*.pem`, …). See [configuration.md#ignore-rules](CONFIGURATION.md).

### My `.env` never appears in snapshots
Correct — secrets are excluded by default (`.env`, `.env.*`, `.ssh`, `.aws`,
`*.pem`, `*.key`, `${HOME}/.ssh` paths, `id_rsa`, `id_ed25519`, …). If you keep a
legitimately-needed file that matches a default ignore, add it back by editing
`ignorePatterns` in `.chronovault/config.json` (not recommended for secrets).

### Small projects: "no verified checkpoint yet"
`chronovault checkpoint --label first` — a BROKEN non-verified snapshot is never a
recovery target. Make `chronovault health` pass first.

## Web dashboard

### Page loads but nothing protects
`chronovault ui` binds to **127.0.0.1 only**. Verify you opened
`http://localhost:7723` (not `http://<your-ip>:7723`).

### Port 7723 already in use
`chronovault ui --port 9000`.

### Dashboard action stuck on "running"
The CLI may be waiting on the interactive trust prompt. Run the same command from a
terminal (`chronovault restore --yes` or accept the prompt once) so the allowlist is
populated, then retry.

## IDE plugins

### "could not open dashboard" (VS Code)
Check the Output → ChronoVault channel for the embedded CLI error; usually a missing
CLI on PATH. Set `chronovault.cliPath`.

### IntelliJ: notifications don't appear
Non-Balloon display requires a notification group; v1.0.0 registers
`CHRONOVAULT` with `displayType="BALLOON"`. If you still see nothing, check that the
plugin built with `cd intellij-plugin && ./gradlew buildPlugin` rather than a stale jar.

## Reporting a bug

Include:
```bash
CHRONOVAULT_DEBUG=1 chronovault state --json
chronovault history
java -version
```
and open an issue at <https://github.com/100raav/chronovault/issues>.
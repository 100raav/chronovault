# Installation

Everything local, nothing in the cloud. CHRONOVAULT is a single Java 21+ executable
plus two optional IDE plugins.

## Requirements

- **Java 21+** (JRE or JDK) — [Adoptium Temurin 21](https://adoptium.net) recommended.

## From source (recommended)

```bash
git clone https://github.com/100raav/chronovault.git
cd chronovault
./gradlew :cli:installDist
export PATH="$PWD/cli/build/install/chronovault/bin:$PATH"
```

Verify:

```bash
chronovault version
```

To make it permanent, append the `export` line to your shell profile (using the
absolute path, e.g. `/Users/you/chronovault/cli/build/install/chronovault/bin`).

## Prebuilt CLI (releases)

When a v1.0.0 release is cut, the CLI installers (`.zip`/`.tar.gz`, per-OS archive)
are attached to the GitHub release. Unpack and add `bin/` to `PATH`. Until then,
install from source above.

## IDE plugins

- **VS Code** — install the bundled VSIX:

  ```bash
  code --install-extension vscode-extension/chronovault-1.0.2.vsix
  # or: Extensions view → ⋯ → Install from VSIX...
  ```

  Marketplace listing (after release): see [PUBLISHING.md](PUBLISHING.md).

- **IntelliJ IDEA** — `Settings → Plugins → ⚙ → Install Plugin from Disk...`, pick
  `intellij-plugin/build/distributions/chronovault-intellij-1.0.2.zip`.

Both plugins need the `chronovault` CLI on `PATH` (VS Code: override with
`chronovault.cliPath`).

## Upgrading

`git pull` in the repo, re-run `./gradlew :cli:installDist`, re-export `PATH`.
Your vaults (`.chronovault/` folders) and checkpoints are untouched by CLI upgrades.

## Troubleshooting

- `command not found` — `PATH` export not persisted or wrong absolute path.
- `Java version ... is too old` — install Java 21 and make it the default
  (`java -version`).
- macOS `unidentified developer` — `xattr -dr com.apple.quarantine <path>` on a
  source/CI-built binary, or use the source install.

See [TROUBLESHOOTING.md](TROUBLESHOOTING.md) for more.
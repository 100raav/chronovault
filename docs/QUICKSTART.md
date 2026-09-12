# Quickstart

**Return to the moment your code still worked.** Five minutes to your first verified
checkpoint and a safe recovery.

## 1. Install the CLI

System-wide install:

```bash
./gradlew :cli:installDist
export PATH="$PWD/cli/build/install/chronovault/bin:$PATH"
```

Or add the line above (with the absolute path) to your shell profile. Full options in
[INSTALLATION.md](INSTALLATION.md).

## 2. Initialize

```bash
cd your-project
chronovault init
```

This creates `.chronovault/`, detects your project type, and stores a health profile
(match markers + a build/test command). Check detection confidence:

```bash
chronovault detect
```

## 3. Record a verified baseline

```bash
chronovault checkpoint --label "baseline"
```

Your real build/test command runs first. The snapshot is recorded **`VERIFIED HEALTHY`**
and becomes the recovery target.

## 4. Break something, then get fixed

```bash
# ... edit a file, introduce a bug ...
chronovault diagnose        # WHAT BROKE IT? — evidence cards
chronovault plan            # preview the exact restore (0 files changed so far)
chronovault restore         # protect → restore → verify → commit, or auto-rollback
```

If post-restore verification fails, CHRONOVAULT automatically rolls back to the exact
state it protected. Detail: [Recovery guide](recovery-guide.md).

## 5. Glance at the web dashboard

```bash
chronovault ui --open
```

Timeline, restore wizard, diffs, and storage stats on `http://localhost:7723`
(loopback only). See [Web dashboard](web-ui.md).

## Next

- [Configuration](CONFIGURATION.md) — profiles, ignore rules, trust policy
- [Supported projects](SUPPORTED_PROJECTS.md) — the 9 built-in adapters
- [Health profiles](HEALTH_PROFILES.md) — how verification works
- [CLI reference](cli.md) — every command
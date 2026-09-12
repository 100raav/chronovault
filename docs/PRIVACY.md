# Privacy

CHRONOVAULT is **local-first by design**: your code, snapshots, health evidence, and
recovery logs never leave your machine.

## What is stored, where

| Data | Location | Notes |
| --- | --- | --- |
| Snapshots (content-addressable) | `.chronovault/store/` | deduplicated; verified metadata in `.chronovault/checkpoints/repo/` |
| Health evidence | `.chronovault/checkpoints/repo/` | profile, per-check results, timing, toolchain |
| Recovery journal | `.chronovault/journal/` | operation ids, stages, timestamps |
| Config + trust allowlist | `.chronovault/config.json` | machine-readable, cleared/edited by you |

Everything lives **inside the project folder** you initialize. Deleting the folder
(and its `.chronovault/`) removes all vault data. No account, no registry, no
telemetry, no analytics, no first-run "phone home".

## CHRONOVAULT data vs. your project's tooling

CHRONOVAULT itself performs no outbound network I/O except the loopback-only
dashboard server you start explicitly. Two distinct things must not be conflated:

1. **CHRONOVAULT data handling** — snapshots, health evidence, and recovery journal
   are written to `.chronovault/` on your disk and never transmitted.
2. **Your project's build/test commands** — the health profile you configure runs
   your own tooling (`mvn`, `gradle`, `npm`, `go`, …). Because those are *your*
   commands, they can access whatever your normal build can — including package
   registries or remote dependencies. That is standard project behavior, not
   CHRONOVAULT telemetry.

## What never leaves

- Your source files and the file names in your project (except the local vault).
- File contents of `.env`, `.ssh`, `*.pem`, `*.key`, `id_rsa`, `id_ed25519`, and the
  other default-ignored secret paths — they are excluded from snapshots entirely
  ([configuration.md#ignore-rules](CONFIGURATION.md)).
- Health check output beyond what your own `.chronovault/config.json` profile runs
  locally.

## Network surface

- `chronovault ui` starts an HTTP server that binds only to `127.0.0.1` (loopback).
  Port 7723 by default (`--port` to change). CORS is restricted to localhost
  origins; responses are not cached; the UI requires typed confirmation for
  recovery.
- The CLI never connects outbound. Adapters invoke your local build tools only.

## Third-party code

The CLI bundles Jackson (JSON) and the Java standard library. The VS Code and
IntelliJ plugins are thin wrappers that invoke the local CLI — they collect no data
and send nothing anywhere.

## Your controls

- `chronovault trust` — gate which health commands may run (`ALLOWLIST_ONLY` refuses
  anything not explicitly allowed).
- `.chronovault/config.json` — exact say over ignore rules, retention, and profiles.
- `chronovault gc` — reclaim storage per your retention policy.

Questions or concerns: open an issue at
<https://github.com/100raav/chronovault/issues>.
# Web dashboard

`chronovault ui` serves a local dashboard at `http://localhost:7723` (no browser plugin,
no build system — plain HTML/CSS/JS served by the CLI's embedded HTTP server).

```
chronovault ui --open          # default port 7723
chronovault ui --port 9000     # custom port
```

## What you get

- **Status ring** — overall protection status (PROTECTED / UNPROTECTED / BROKEN) with
  health pass counts.
- **Timeline** — an interactive SVG timeline of checkpoints and recovery operations,
  zoomable and labeled.
- **Checkpoint table** — every checkpoint with status, health evidence, timestamps, and
  per-row actions (`Diff`, `Restore`, `Copy id`, `Pin`).
- **Diff modal** — snapshot-to-snapshot diff with file-level changes.
- **Restore wizard** — shows the `RestorePlan` (files + bytes) and requires a typed
  confirmation before executing a real recovery, mirroring the CLI trust model.
- **Diagnose panel** — "What broke it?" evidence cards rendered from the diagnostics API.
- **Operations log** — live recovery history.
- **Command palette** — `Ctrl/Cmd+K` for every action.
- **Themes** — dark, light, and high-contrast (cycle with the theme button; stored in
  `localStorage`).

## API

The dashboard is driven by a small JSON API backed by the exact same core services as
the CLI, so what you see is what the CLI would do.

| Endpoint | Description |
| --- | --- |
| `GET /api/state` | Vault summary + storage stats |
| `GET /api/checkpoints` | Checkpoint list with evidence |
| `GET /api/checkpoint?id=ID` | Single checkpoint detail |
| `GET /api/health` | Run and return a health check |
| `GET /api/meta` | Project metadata + detected adapter |
| `GET /api/diff?a=ID&b=ID` | Snapshot diff |
| `GET /api/plan?to=ID` | Restore plan preview |
| `POST /api/recover` | Confirm and execute recovery `{target, confirm}` |
| `GET /api/diagnose` | Diagnostics evidence cards |
| `GET /api/history` | Recovery operation log |
| `GET /api/storage` | Object/store statistics |
| `GET /api/events` | Server-Sent Events stream (heartbeat every 15 s) |
| `GET /static/*`, `/favicon.ico` | UI assets (index.html, styles.css, app.js, icon.svg) |

## Security

- The server binds by default to loopback only.
- Recovery requires an explicit confirmation token from a fresh plan — a stale page or
  CSRF-style GET cannot trigger a restore.
- All endpoints validate ids before touching the vault; errors are returned as JSON
  with status 400/404/409 rather than stack traces.
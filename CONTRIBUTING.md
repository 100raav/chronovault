# Contributing

CHRONOVAULT is licensed under its [EULA](EULA.md) — like many commercial tools,
contributions are welcome but the project is not open-source. See the relevant
documents before contributing code, docs, or reports.

## Issues & feedback

- Bug reports: include `chronovault state --json`, `chronovault history`,
  `java -version`, and OS.
- Feature requests: describe the workflow you're protecting, not just the button.
- Security issues: do **not** open a public issue with exploit details; prefer a
  private disclosure via the repository owner.

## Code

1. Read [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for layout and conventions.
2. `./gradlew build` — the 43-test suite must stay green.
3. For UI changes, `node --check cli/src/main/resources/web/app.js` and update
   `docs/web-ui.md` if behavior changes.
4. For new adapters, add a runnable `sample-projects/` fixture.
5. Run `scripts/release.sh` before submitting anything marked as a release change.

## Pull requests

- One logical change per PR.
- Mention the checkpoint/restore safety invariants if your change touches snapshots,
  restore, or the content store — those paths are audited most strictly.
- Never add secrets, tokens, or your `~/.ssh`/`~/.aws` into the repo; the release
  gate scans for these and will fail.

## Docs

Improvements to `docs/` are very welcome. Keep the tone factual; never invent
metrics, screenshots, or claims about marketplace status — those are verified only
during the release process.
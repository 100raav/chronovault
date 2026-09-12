# Health profiles

A health profile is a small set of **checks** the vault runs to decide whether the
current working tree is healthy enough to record as a verified checkpoint — and to
re-verify it after a restore.

## Structure

```json
{
  "healthProfiles": {
    "default": {
      "matchMarkers": ["pom.xml"],
      "runOnMarkers": true,
      "checks": [
        { "id": "build", "name": "Build", "kind": "BUILD",
          "command": ["mvn", "-q", "compile"], "required": true,
          "timeoutSeconds": 300, "workingSubdir": ".", "failFast": true }
      ]
    }
  },
  "activeHealthProfile": "default"
}
```

- `matchMarkers` — files that identify the project. Because the profile only runs
  when `runOnMarkers` is true, adapters keep accidental cross-project mis-runs away.
- `command` — argv array. The command line is built by joining with single spaces
  (`mvn -q compile`). Multiple checks run in the profile's declared order.
- `required` — a failing required check makes the state BROKEN (the snapshot is still
  recorded as a "so this is where it broke" marker, but it is **never a recovery
  target**). Optional checks can fail without marking the state broken.
- `failFast` — stop the remaining checks as soon as this one fails.
- `timeoutSeconds` — a check that exceeds this is treated as failed.
- `workingSubdir` — run the command relative to this project subdirectory.

## Trust gating

Every check command is passed through the trust policy before it executes
([configuration.md#trust-policy](CONFIGURATION.md)):

- `ASK` — approved by the interactive `init` consent (or `--yes`).
- `ALLOWLIST_ONLY` — must be listed in `.chronovault/config.json` → `allowlist`
  (`chronovault trust --allow "mvn test"`); otherwise the check reports an ERROR
  ("command not approved") and the profile cannot pass.
- `ALLOW_ALL` — always runs.

A profile with **zero checks** is config error state: it can never be healthy
(overall status `ERROR`), so an empty profile can never produce a verified
checkpoint.

## API / CLI integration

`chronovault health` runs the active profile and prints PASS/FAIL/ERROR/SKIPPED per
check with timing and toolchain evidence. The web dashboard shows the same checks as
`/api/v1/health`.

## Why evidence matters

Checkpoint records store the profile id, per-check results, timing, and toolchain —
so a restore target is provably healthy, and post-restore verification has the exact
same evidence format as the original checkpoint. That symmetry is what makes
automatic rollback trustworthy.
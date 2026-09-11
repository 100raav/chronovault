# Recovery guide

A factual walkthrough of the full "oh no, it broke" workflow on a real sample project.

## Scenario

You are working in `sample-projects/node`. You made a healthy baseline, then changed
`src/index.js` and broke the build. You want to get back to the last working moment —
and understand what broke — without losing your work.

## 1. Establish a verified baseline

```bash
cd sample-projects/node
export CHRONOVAULT_PROJECT=$PWD
bin/chronovault init

bin/chronovault checkpoint --label baseline
```

Only when your **real** tests pass is the checkpoint marked `VERIFIED`:

```
✓ CHECKPOINT cp-a6a808c1 VERIFIED HEALTHY
  2/2 checks passed
```

`bin/chronovault state` now reports:

```
Status          : PROTECTED — cp-a6a808c1 verified at 23:42:45
```

## 2. Break something

Suppose you accidentally remove `.reverse()` from the palindrome check. Re-checkpoint:

```bash
bin/chronovault checkpoint --label oops
```

Health fails, the snapshot is preserved as **BROKEN** (useful forensic state, never a
recovery target):

```
✗ CHECKPOINT cp-95bf161d RECORDED BROKEN
```

## 3. Diagnose — what broke it?

```bash
bin/chronovault diagnose
```

```
WHAT BROKE IT?
  Last healthy   : cp-a6a808c1 at 23:42:45
  Changes since  : 1
  [FACT] Tests failed (exit 1)
  [OBS]  Modified src/index.js
  [HYP]  Likely culprit — latest changed files
```

Evidence is deterministic: the failing check, the changed files since the last verified
checkpoint, and the most probable culprit.

## 4. Preview the restore

```bash
bin/chronovault plan
```

```
RECOVERY PREVIEW — return to cp-a6a808c1
  1 modified · 0 added · 0 deleted · 0 renamed
  448 B to restore · current work will be protected
```

## 5. Restore

```bash
bin/chronovault restore --yes
```

```
▸ PLANNING — Planning recovery to cp-a6a808c1
▸ PROTECTING CURRENT STATE — Snapshot complete — 4 files
▸ RESTORING FILES
▸ VERIFYING — Running Build…
▸ VERIFYING — Running Tests…
▸ COMMITTING
✓ STATE RESTORED
  cp-a6a808c1 is now your active state
```

Your broken `src/index.js` is back to the verified good version, your tests pass, and
your broken state was **protected and preserved** as a checkpoint — nothing lost.

`bin/chronovault checkpoints` shows the full story:

```
cp-95bf161d  ✗ BROKEN   23:42:56   ← the moment it broke
cp-a6a808c1  ✓ VERIFIED 23:42:45   ← the moment it worked
```

## What if the restore itself fails?

The verification step runs your tests *after* restoring. If they fail (e.g. the target
snapshot is stale and incompatible with new generated files), CHRONOVAULT
automatically **rolls back**:

```
✗ RECOVERY FAILED — verification did not pass
✓ Rollback complete — your pre-recovery state has been restored
```

You keep your exact pre-recovery working tree, and `history` records the attempt.

## Using the web dashboard

```bash
bin/chronovault ui --open
```

The dashboard shows the same state: timeline, checkpoint table, diff, restore wizard,
and the diagnosis. The restore wizard requires a fresh plan plus a typed confirmation —
the UI cannot bypass the trust model.

## Write-ahead journal (crash safety)

Every stage above is journaled to `.chronovault/journal.ndjson` and fsync'd **before**
the state change it protects. If you force-quit mid-recovery, the next command
reconciles the journal against the database and finishes or rolls back the interrupted
operation — you are never left with a half-restored tree.

## Checklist for production use

1. `init` once per project (adapter auto-detected, profile stored).
2. Checkpoint at every meaningful milestone (`--label` helps).
3. On breakage: `diagnose` → `plan` → `restore`.
4. Periodically `gc --dry-run`, then `gc` (pins survive).
5. Keep `.chronovault/` out of version control (see `.gitignore`).
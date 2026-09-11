# Sample projects

Runnable demo projects for trying CHRONOVAULT end-to-end. No network or package install
needed.

| Project | Toolchain | Verifies with |
| --- | --- | --- |
| `java-maven` | Maven (not installed on this machine — docs only) | `mvn test` |
| `node` | Node.js ✓ | `npm run build` → `npm test` |
| `python` | Python 3 ✓ | `python3 -m unittest discover -s test` (stdlib) |

## Try it (Node — nothing to install)

```bash
./gradlew :cli:installDist
export PATH="$PWD/cli/build/install/chronovault/bin:$PATH"

chronovault init
chronovault checkpoint --label baseline
chronovault state
chronovault ui --open
```

Then edit `sample-projects/node/src/index.js`, re-checkpoint, and run:

```bash
chronovault diagnose
chronovault plan
chronovault restore --yes     # back to working — with rollback protection
```

(The CLI binary lives at `cli/build/install/chronovault/bin/chronovault` after building.)

> The demo intentionally includes a real breakage your build/tests can detect:
> `npm run build` regenerates `dist/`, and the unit tests are genuine Node assertions.
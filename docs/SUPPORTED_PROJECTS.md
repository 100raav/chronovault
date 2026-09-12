# Supported projects

CHRONOVAULT detects your project type with a confidence score (`chronovault detect`)
and generates a matching health profile on `init`.

## Built-in adapters

| Adapter | Marker files | Default build command | Default test command | Confidence |
| --- | --- | --- | --- | --- |
| Java (Maven) | `pom.xml` | `mvn -q -DskipTests compile` | `mvn test` | 0.95 |
| Java/Kotlin (Gradle) | `build.gradle` / `build.gradle.kts` | `gradle build` | `gradle test` | 0.90 |
| Node.js | `package.json` | `npm run build` (only if a `build` script exists) | `npm test` (only if a `test` script exists) | 0.90–0.95 |
| Python | `setup.py`, `pyproject.toml`, `Pipfile`, `requirements.txt`, or `.py` sources | — (no build step) | `python3 -m pytest`, else `python3 -m unittest discover -s test` | 0.80 |
| Rust | `Cargo.toml` | `cargo build` | `cargo test` | 0.90 |
| Go | `go.mod` | `go build ./...` | `go test ./...` (plus `go vet ./...`) | 0.90 |
| .NET | `*.sln`, `*.csproj` | `dotnet build` | `dotnet test` | 0.90 |
| C/C++ | `CMakeLists.txt`, `Makefile`, or `src/main.{c,cpp}` | `cmake` or `make` | — | 0.80 |
| Generic | `Makefile` or `src/` | `make` (when a Makefile exists) | — | 0.20–0.50 |

Each adapter lives in its own Gradle module under [`../adapters`](../adapters) and is
discovered at runtime via the Java `ServiceLoader`, so third-party adapters can ship
as jars on the classpath without modifying core.

## Important: commands are suggestions

The commands above are **auto-detected defaults only** — real projects differ. On
`init` you review the proposed health profile before it is stored, and you can edit
`.chronovault/config.json` afterwards (see
[configuration.md#health-profiles](CONFIGURATION.md)). Adapters report a `toolchain`
(such as `rustc --version` or `gcc --version`) used for provenance evidence.

## Activating another adapter

Touch the marker file for the adapter you want, then re-detect:

```bash
touch pyproject.toml
chronovault init
chronovault detect   # shows every match ranked by confidence
```

If nothing matches, the Generic adapter still gives full checkpoint/restore
protection with a `make` (or custom) build command.
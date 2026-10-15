# Build & run

Three build paths: inside Docker (nothing to install), locally with the JDK, or in CI/release.

## Docker only

```bash
scripts/try.sh                                   # docker compose up -d --build + health wait + logins (make try)
docker compose build oms                         # just the image; same Dockerfile the release workflow uses
docker build -f backend/oms-app/Dockerfile -t intent-oms:dev .   # from the repository root (context = repo)
```

`backend/oms-app/Dockerfile` is multi-stage: `node:22-alpine` runs `npm ci && npm run build` for `ui/`, the dist is
copied into `backend/oms-app/src/main/resources/static/`, `maven:3.9-eclipse-temurin-21` runs `mvn -DskipTests
package`, and the jar plus `config/` land on `eclipse-temurin:21-jre-alpine` as the non-root `oms` user. BuildKit cache
mounts keep `~/.npm` and `~/.m2` between builds; `.dockerignore` excludes `target/`, `node_modules/`, `.env` and docs,
so the context is ~12 MB. Build args: `VERSION` and `SOURCE_URL` (OCI labels).

## Local JDK

```bash
scripts/doctor.sh --source                     # JDK 21, Maven 3.9+, Node 22, Docker, ports
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home   # or wherever Temurin 21 lives
docker compose up -d postgres redis            # Postgres on localhost:5440 (db/user/pass: oms/oms/oms)
scripts/build-ui.sh                            # optional: React UI → backend/oms-app/src/main/resources/static
cd backend && mvn -q -DskipTests clean install # all modules (clean refreshes nested jars in the app)
mvn -q -pl oms-<module> -am install -DskipTests  # one module + upstream
mvn -q -pl oms-<module> test                   # module tests (Testcontainers; Docker required)
cd oms-app && JAVA_HOME=$JAVA_HOME mvn spring-boot:run
```

`scripts/bootstrap.sh` strings these together (and `--skip-build` restarts an existing jar); `make build`, `make ui`,
`make run`, `make test` are the Makefile equivalents. The UI alone: `cd ui && npm ci && npm run dev` (Vite on 5173,
proxying `/api`, `/mcp` and `/actuator` to 8080). SBOM: `mvn -Psbom -DskipTests package` writes CycloneDX files
under `backend/target/`.

## CI and release

`.github/workflows/ci.yml` builds and tests backend and UI on every push and PR and scans dependencies, secrets and
the image. `.github/workflows/release.yml` (tag `v*`) builds the multi-arch image, pushes it to GHCR, signs it and
attaches jar + SBOMs to the GitHub release: see `docs/DEPLOYMENT.md` section 12. A Codespace (`.devcontainer/`) has
the full toolchain preinstalled.

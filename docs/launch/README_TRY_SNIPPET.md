# README snippet: badges and "Try it" (for the README owner)

Paste the badge row under the `# Intent OMS` title and the "Try it in ten minutes" block in place of (or above) the
current "Quick start" section. The URLs below already point at `github.com/pradeepvanalli/Intent_Based_OMS` — this
snippet predates that decision; README.md itself already carries the same, now-live badges and links.

---

````markdown
[![CI](https://github.com/pradeepvanalli/Intent_Based_OMS/actions/workflows/ci.yml/badge.svg)](https://github.com/pradeepvanalli/Intent_Based_OMS/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/pradeepvanalli/intent_based_oms?include_prereleases&label=release)](https://github.com/pradeepvanalli/Intent_Based_OMS/releases)
[![Container](https://img.shields.io/badge/ghcr.io-pradeepvanalli%2Fintent_based_oms-blue?logo=docker)](https://github.com/pradeepvanalli/Intent_Based_OMS/pkgs/container/intent_based_oms)
[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/pradeepvanalli/Intent_Based_OMS?quickstart=1)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-green.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://adoptium.net)
[![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL 16](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org)
[![Docs](https://img.shields.io/badge/docs-pradeepvanalli.github.io-informational)](https://pradeepvanalli.github.io/Intent_Based_OMS/)
````

````markdown
## Try it in ten minutes

Four seeded sample tenants (`demo`, `retail`, `electronics`, `apparel`), six users each (`admin`/`admin`, `ops`/`ops`,
`cs`/`cs`, `risk`/`risk`, `finance`/`finance`, `strategy`/`strategy`). Works without any AI key (rules and defaults);
add `OPENROUTER_API_KEY` to switch on Jev and the LLM.

**1. Docker, nothing else** (published image)

```bash
curl -fsSL https://raw.githubusercontent.com/pradeepvanalli/Intent_Based_OMS/main/docker-compose.quickstart.yml -o docker-compose.yml && docker compose up -d
```

Open http://localhost:8080 → tenant `demo`, user `admin`, password `admin`.

**2. In the browser** — [![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/pradeepvanalli/Intent_Based_OMS?quickstart=1)
builds and starts everything in a 2-core / 8 GB codespace; click the **Intent OMS** port when it appears.

**3. From source**

```bash
git clone https://github.com/pradeepvanalli/Intent_Based_OMS.git && cd Intent_Based_OMS
scripts/try.sh            # Docker only: builds the image (UI + backend), starts Postgres/Redis/OMS, prints logins, opens the browser
scripts/bootstrap.sh      # or: local JDK 21 + Maven + Node 22 for development
```

`scripts/try.sh --down` stops, `--reset` starts over, `scripts/doctor.sh` checks your machine. Windows:
`.\scripts\try.ps1`. Everything else: [docs/QUICKSTART.md](docs/QUICKSTART.md).
````

---

Notes for the README owner:

* The Codespaces badge deep-links to `codespaces.new/<owner>/<repo>?quickstart=1`, which uses `.devcontainer/devcontainer.json`.
* The Container badge is static (shields.io has no GHCR tag endpoint); the link goes to the package page.
* The Docs badge assumes `.github/workflows/pages.yml` has published `docs/site/` once (Settings → Pages → Source: GitHub Actions).
* Release badge shows nothing until the first `v*` tag is pushed (`.github/workflows/release.yml`).
* Keep the existing "Quick start" details (what the seed contains, `scripts/demo.sh`, `scripts/usecases/run-all.sh`,
  `scripts/new-tenant.sh`) below this block or move them to `docs/QUICKSTART.md`, which already covers them.

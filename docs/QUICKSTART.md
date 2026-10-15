# Quick start: try Intent OMS in ten minutes

Three ways in, from "nothing installed" to "I want to hack on it". All of them end at http://localhost:8080 with four
seeded sample tenants and the same logins. Not sure your machine is ready? `scripts/doctor.sh` tells you what is
missing and how to fix it.

| Path | You need | Time | Command |
|---|---|---|---|
| **1. Docker one-liner** | Docker Desktop / Engine with compose v2 | ~2 min (image pull) | `curl -fsSL https://raw.githubusercontent.com/pradeepvanalli/Invent_Based_OMS/main/docker-compose.quickstart.yml -o docker-compose.yml && docker compose up -d` |
| **2. GitHub Codespaces** | a browser | ~8 min first build, then 1 min | Code → Codespaces → Create codespace on `main` |
| **3. From source** | Docker, or JDK 21 + Maven + Node 22 | ~10 min | `scripts/try.sh` (containers) or `scripts/bootstrap.sh` (local JVM) |

## 1. Docker one-liner (published image)

Pulls `ghcr.io/pradeepvanalli/invent_based_oms:latest` plus PostgreSQL 16 and Redis. No Java, Node or Maven on your machine.

```bash
mkdir intent-oms && cd Invent_Based_OMS
curl -fsSL https://raw.githubusercontent.com/pradeepvanalli/Invent_Based_OMS/main/docker-compose.quickstart.yml -o docker-compose.yml
docker compose up -d
docker compose logs -f oms          # Ctrl-C when you see "Demo seed summary"; the first start takes about a minute
```

Open http://localhost:8080 and log in with tenant `demo`, user `admin`, password `admin`. Pin a version with
`OMS_IMAGE=ghcr.io/pradeepvanalli/invent_based_oms:v0.1.0 docker compose up -d`; change the host port with `OMS_PORT=8090`.
From a checkout, `scripts/try.sh --pull` (or `.\scripts\try.ps1 -Pull` on Windows) runs the same file and additionally
waits for health, prints the login table and opens the browser.

> `intent-oms` in the image name is the GitHub owner the release workflow publishes under (`ghcr.io/<owner>/<repo>`).
> Running a fork? Replace it, or `export OMS_IMAGE=ghcr.io/<you>/intent-oms:latest`.

## 2. GitHub Codespaces / VS Code Dev Containers

Click **Code → Codespaces → Create codespace on main** (or "Reopen in Container" in VS Code with the Dev Containers
extension). `.devcontainer/devcontainer.json` provides Temurin JDK 21, Maven, Node 22 and Docker; on creation it
builds the UI and the backend (6-8 minutes), and on every start it runs `scripts/bootstrap.sh`, which brings up
PostgreSQL + Redis, starts the service and seeds the demo tenants. When the **Intent OMS** port (8080) appears in the
PORTS panel, open it in the browser and log in.

Machine size: the 2-core / 8 GB Codespace is enough (peak need is about 5 GB); choose 4-core / 16 GB for a faster
first build. To switch the AI providers on, add a Codespaces secret named `OPENROUTER_API_KEY` (GitHub → Settings →
Codespaces → Secrets) before creating the codespace; `post-create.sh` copies it into `.env`.

Useful commands inside the codespace: `scripts/bootstrap.sh --stop`, `make build && scripts/bootstrap.sh
--skip-build` after a backend change, `cd ui && npm run dev` for the hot-reloading UI on port 5173 (proxied to 8080).

## 3. From source

### 3a. Containers only (`scripts/try.sh`)

```bash
git clone https://github.com/pradeepvanalli/Invent_Based_OMS.git && cd Invent_Based_OMS
scripts/try.sh                     # Windows PowerShell: .\scripts\try.ps1      make equivalents: make try / reset / down
```

Checks Docker and compose, creates `.env` from `.env.example` (copying `OPENROUTER_API_KEY` from your shell if it is
set), runs `docker compose up -d --build` (the Dockerfile builds the UI in a Node stage and the backend in a Maven
stage, so nothing but Docker is needed; the first build takes 5-10 minutes, later ones seconds thanks to BuildKit
caches), waits for `/actuator/health`, waits for the demo seed, prints the login table and opens the browser.
`scripts/try.sh --down` stops it (data kept), `--reset` wipes the database and starts fresh (asks first; `--yes`
skips the question), `--logs` follows the log, `--pull` uses the published image instead of building.

### 3b. Local JVM (`scripts/bootstrap.sh`)

For development: JDK 21, Maven 3.9+, Node 22 (optional: without it the API runs but the UI is not served) and Docker
for PostgreSQL/Redis. `scripts/bootstrap.sh` does the same checks, builds with your local toolchain, starts the jar
on :8080 with logs in `.oms/oms.log` and stops with `--stop`. Details: `docs/GETTING_STARTED.md`, `CONTRIBUTING.md`,
`docs/BUILD.md`.

## What you see after login

* **Control tower** (home): the order pipeline by state, age and channel, SLA breach risk, open exceptions and tasks.
  Each sample tenant starts with ~25 orders in various states (sourcing, released, shipped, delivered, returns).
* **Orders**: open any order to see its statechart, quantity-level status, holds, the decision trace ("why was it
  sourced from DC-NJ?") and the event ledger behind it.
* **Policy studio** (`/studio`): every decision, state model, compliance rule and playbook as versioned YAML with lint,
  impact diff against history, golden-set gates and approvals. The copilot panel turns an English request into a draft.
* **Agents**: the registry, mandates and the MCP server (`POST /mcp`) and UCP endpoint (`/ucp/v1`) shopping agents use.
* **Swagger UI** at `/swagger-ui`, GraphQL at `POST /graphql`, Prometheus metrics at `/actuator/prometheus`.

Then drive an order end to end from the command line: `scripts/demo.sh` (promise → capture → sourcing → ship →
deliver → return → KPIs) and `scripts/usecases/run-all.sh` for the business scenarios in `docs/DEMO_USE_CASES.md`.

## The four sample tenants

Every path seeds `OMS_DEMO_TENANTS=demo,retail,electronics,apparel` from `config/tenants/<tenant>/`. Each tenant has
the same six bootstrap users; the password is the user name.

| Tenant | What it models | Try this |
|---|---|---|
| `demo` | General retailer: 8 nodes, 40 SKUs, 6 state models, 14 decisions with golden sets, 3 agents | `scripts/demo.sh`, the copilot in the studio |
| `retail` | Omnichannel retail with stores, BOPIS and ship-from-store sourcing | sourcing decisions and split policies |
| `electronics` | High-value, serialised goods: fraud and risk tiers, approvals, strict compliance rules | holds, tiered approvals, risk scoring |
| `apparel` | Returns-heavy fashion: sizes, keep-it refunds, return-abuse scoring, dispositions | returns flow, refund guardrails |

| User | Role | Can |
|---|---|---|
| `admin` | admin | everything, including tenant configuration and API keys |
| `ops` | ops_analyst | holds, releases, bulk actions, tasks, exceptions |
| `cs` | cs_rep | order changes, returns, refunds, appeasements |
| `risk` | risk_analyst | return decisions, agent admin, decision approvals and overrides |
| `finance` | finance_controller | refund and margin-floor approvals |
| `strategy` | strategy_lead | policy studio drafts, simulations, bulk actions |

The login form asks for tenant, user and password (`apparel` / `cs` / `cs`, for example). API: `POST
/api/v1/auth/login {"tenantId":"demo","userId":"admin","password":"admin"}` returns a bearer token
(`scripts/token.sh admin admin demo`). Your own tenant: `scripts/new-tenant.sh acme "Acme Outdoors"`.

## Switching the AI providers on

Without a key everything runs in **degraded mode**: L0 rules, deterministic heuristics and defaults decide, and every
decision trace says so. One OpenRouter key enables both Jev (typed L1 decisions) and the LLM (L2 reasoning, copilot):

```bash
export OPENROUTER_API_KEY=sk-or-v1-...
scripts/try.sh                        # copies the key into .env and restarts the container
# or: add OPENROUTER_API_KEY=... to .env and `docker compose up -d`; for the one-liner, put it in a .env next to docker-compose.yml
```

Direct keys work too: `TYPESAFE_API_KEY` (Jev), `ANTHROPIC_API_KEY` or `OPENAI_API_KEY` (LLM). `GET
/api/v1/decisions/providers` shows what is active; `ai.budget_usd_per_day` per tenant caps spend. Model names and
budgets are in `.env.example` and `docs/CONFIGURATION.md`.

## Resetting, stopping, updating

| Want to | Docker paths | Codespaces / bootstrap |
|---|---|---|
| Stop, keep data | `scripts/try.sh --down` or `docker compose down` | `scripts/bootstrap.sh --stop` |
| Start again from an empty database | `scripts/try.sh --reset` (`make reset`), or `docker compose down -v && docker compose up -d` | `docker compose down -v postgres && scripts/bootstrap.sh --skip-build` |
| Pick up a new version | `git pull && scripts/try.sh` (rebuilds), or `docker compose pull && docker compose up -d` for the published image | `git pull && scripts/bootstrap.sh` |
| See the log | `scripts/try.sh --logs` | `tail -f .oms/oms.log` |
| Check the environment | `scripts/doctor.sh` (`make doctor`) | same |

The demo configuration (dev users, default JWT secret, demo seed) is for your laptop only. Anything reachable from
the internet goes through `docker-compose.prod.yml` or the Helm chart (`deploy/helm/intent-oms`) with the `prod`
profile, which refuses to start with these settings. See `docs/DEPLOYMENT.md`.

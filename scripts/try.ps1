<#
.SYNOPSIS
  Try Intent OMS on Windows with nothing but Docker Desktop (PowerShell twin of scripts/try.sh).

.DESCRIPTION
  Builds (or pulls) the image, starts PostgreSQL + Redis + the service with the four demo tenants seeded, waits until
  it is healthy, prints the logins and opens the browser. Idempotent: run it again after a git pull.

    .\scripts\try.ps1            build from this checkout and start           (docker-compose.yml)
    .\scripts\try.ps1 -Pull      use the published image instead of building  (docker-compose.quickstart.yml)
    .\scripts\try.ps1 -Reset     wipe the database volume and start fresh (asks first; -Yes skips the question)
    .\scripts\try.ps1 -Down      stop the containers (data kept; -Reset -Down also removes the data)
    .\scripts\try.ps1 -Logs      follow the service log

  Environment: OPENROUTER_API_KEY (copied into .env on first run), OMS_PORT (host port, default 8080).
  If PowerShell refuses to run scripts:  Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
#>
[CmdletBinding()]
param(
  [switch]$Pull,
  [switch]$Reset,
  [switch]$Down,
  [switch]$Yes,
  [switch]$NoOpen,
  [switch]$Logs,
  [string]$Image = ""
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root

function Step($m) { Write-Host ""; Write-Host "> $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "  [ok] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  [!]  $m" -ForegroundColor Yellow }
function Fail($m) {
  Write-Host "  [x]  $m" -ForegroundColor Red
  Write-Host "       Docker Desktop for Windows: https://docs.docker.com/desktop/setup/install/windows-install/ (WSL 2 backend recommended)." -ForegroundColor DarkGray
  Write-Host "       On WSL/Git Bash you can also run scripts/doctor.sh for a full environment check." -ForegroundColor DarkGray
  exit 1
}

$ComposeFile = if ($Pull) { "docker-compose.quickstart.yml" } else { "docker-compose.yml" }
if (-not (Test-Path $ComposeFile)) { Fail "$ComposeFile not found in $Root (run this from a checkout of the repository)" }
function Compose { docker compose -f $ComposeFile @args }

$Port = if ($env:OMS_PORT) { $env:OMS_PORT } else { "8080" }
if (-not $env:OMS_PORT -and (Test-Path ".env")) {
  $m = Select-String -Path ".env" -Pattern '^OMS_PORT=(\d+)' | Select-Object -Last 1
  if ($m) { $Port = $m.Matches[0].Groups[1].Value }
}
$Base = "http://localhost:$Port"

# ---------------------------------------------------------------------------------------------------------------- docker
Step "Checking Docker"
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "docker is not installed. Install Docker Desktop and start it." }
docker info *> $null
if ($LASTEXITCODE -ne 0) { Fail "docker is installed but not running. Start Docker Desktop, wait for the whale icon, re-run." }
docker compose version *> $null
if ($LASTEXITCODE -ne 0) { Fail "'docker compose' v2 is required. Update Docker Desktop." }
Ok "docker $(docker version --format '{{.Server.Version}}'), compose $(docker compose version --short)"

# ---------------------------------------------------------------------------------------------------------------- logs / down
if ($Logs) { Compose logs -f oms; exit $LASTEXITCODE }
if ($Down) {
  Step "Stopping Intent OMS"
  if ($Reset) {
    if (-not $Yes) { $a = Read-Host "  This deletes the database volume (all orders, tenants, drafts). Continue? [y/N]"; if ($a -notmatch '^(y|yes)$') { Write-Host "  aborted"; exit 0 } }
    Compose down -v --remove-orphans; Ok "containers stopped and data removed"
  } else {
    Compose down --remove-orphans; Ok "containers stopped (data kept in the 'pgdata' volume; -Reset -Down removes it)"
  }
  exit 0
}

# ---------------------------------------------------------------------------------------------------------------- .env
Step "Environment file"
if (-not (Test-Path ".env")) { Copy-Item ".env.example" ".env"; Ok ".env created from .env.example (every value is a safe local default)" }
else { Ok ".env already present (left as it is)" }
$envText = Get-Content ".env" -Raw
if ($env:OPENROUTER_API_KEY) {
  if ($envText -match '(?m)^OPENROUTER_API_KEY=.+') { Ok "OPENROUTER_API_KEY already set in .env" }
  else { Add-Content ".env" "`n# added by scripts/try.ps1 from the environment`nOPENROUTER_API_KEY=$($env:OPENROUTER_API_KEY)"; Ok "OPENROUTER_API_KEY copied from your environment into .env (Jev + LLM decisions on)" }
} elseif ($envText -match '(?m)^(OPENROUTER_API_KEY|TYPESAFE_API_KEY|ANTHROPIC_API_KEY|OPENAI_API_KEY)=.+') { Ok "AI key found in .env" }
else { Warn "no AI key: the system runs in degraded mode (rules, heuristics and defaults). Set OPENROUTER_API_KEY and re-run to switch AI on." }

# ---------------------------------------------------------------------------------------------------------------- reset
if ($Reset) {
  Step "Resetting the database"
  if (-not $Yes) { $a = Read-Host "  This deletes the database volume (all orders, tenants, drafts) and re-seeds the demo tenants. Continue? [y/N]"; if ($a -notmatch '^(y|yes)$') { Write-Host "  aborted"; exit 0 } }
  Compose down -v --remove-orphans *> $null
  Ok "volume removed; the demo tenants will be seeded again on start"
}

# ---------------------------------------------------------------------------------------------------------------- port
$running = (Compose ps --status running --format '{{.Service}}' 2>$null) -split "`n"
if ($running -notcontains "oms") {
  $busy = Get-NetTCPConnection -LocalPort ([int]$Port) -State Listen -ErrorAction SilentlyContinue
  if ($busy) { Fail "port $Port is already in use by another process (pid $($busy[0].OwningProcess)). Stop it or run: `$env:OMS_PORT=8090; .\scripts\try.ps1" }
}

# ---------------------------------------------------------------------------------------------------------------- up
$env:OMS_PORT = $Port
if ($Pull) {
  Step "Pulling the published image and starting (docker-compose.quickstart.yml)"
  if ($Image) { $env:OMS_IMAGE = $Image }
  Compose pull --quiet oms
  if ($LASTEXITCODE -ne 0) { Fail "could not pull the image. Is it published yet? Build it locally instead: .\scripts\try.ps1 (without -Pull)" }
  Compose up -d --remove-orphans
} else {
  Step "Building the image and starting (docker-compose.yml; the first build takes 5-10 minutes, later ones seconds)"
  Compose up -d --build --remove-orphans
}
if ($LASTEXITCODE -ne 0) { Fail "docker compose up failed. Scroll up for the error; common causes: too little Docker memory (give it 4+ GiB), no network for Maven/npm downloads." }
Ok "containers started"

# ---------------------------------------------------------------------------------------------------------------- wait
Step "Waiting for Intent OMS on $Base"
$ready = $false
for ($i = 0; $i -lt 240; $i++) {
  try { $h = Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 3; if ($h.status -eq "UP") { $ready = $true; break } } catch {}
  $id = (Compose ps -q oms 2>$null)
  if ($id) { $state = docker inspect --format '{{.State.Status}}' $id 2>$null; if ($state -eq "exited" -or $state -eq "dead") { Compose logs --tail 60 oms; Fail "the oms container stopped. Last log lines above (.\scripts\try.ps1 -Logs). Database problem? .\scripts\try.ps1 -Reset" } }
  Write-Host -NoNewline "."; Start-Sleep -Seconds 2
}
Write-Host ""
if (-not $ready) { Compose logs --tail 40 oms; Fail "not healthy after 8 minutes. Log above; .\scripts\try.ps1 -Logs follows it." }
Ok "healthy: $Base/actuator/health"

Write-Host -NoNewline "  seeding the demo tenants"
$seeded = $false; $total = 0
for ($i = 0; $i -lt 90; $i++) {
  try {
    $login = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/auth/login" -ContentType "application/json" -Body '{"tenantId":"demo","userId":"admin","password":"admin"}' -TimeoutSec 5
    if ($login.token) {
      $orders = Invoke-RestMethod -Uri "$Base/api/v1/orders?limit=1" -Headers @{ Authorization = "Bearer $($login.token)" } -TimeoutSec 5
      if ([int]$orders.total -gt 0) { $seeded = $true; $total = $orders.total; break }
    }
  } catch {}
  Write-Host -NoNewline "."; Start-Sleep -Seconds 2
}
Write-Host ""
if ($seeded) { Ok "demo tenant seeded ($total orders on 'demo'; retail, electronics and apparel follow in the background)" }
else { Warn "seeding is still running; it finishes in the background (.\scripts\try.ps1 -Logs)" }

# ---------------------------------------------------------------------------------------------------------------- done
Write-Host ""
Write-Host "Intent OMS is running at $Base" -ForegroundColor White
@"

  UI                 $Base
  Swagger UI         $Base/swagger-ui
  MCP (agents)       POST $Base/mcp        UCP: $Base/ucp/v1   (discovery: $Base/.well-known/ucp)
  Health / metrics   $Base/actuator/health  -  $Base/actuator/prometheus

  Logins (password = user name, same six users on every tenant):

    tenant         admin   ops          cs       risk          finance             strategy
    -----------    -----   ----------   ------   -----------   -----------------   -------------
    demo           admin   ops_analyst  cs_rep   risk_analyst  finance_controller  strategy_lead   general retailer, 25 orders
    retail         admin   ops_analyst  cs_rep   risk_analyst  finance_controller  strategy_lead   omnichannel retail
    electronics    admin   ops_analyst  cs_rep   risk_analyst  finance_controller  strategy_lead   high-value, serialised
    apparel        admin   ops_analyst  cs_rep   risk_analyst  finance_controller  strategy_lead   returns-heavy fashion

    e.g. tenant "demo", user "admin", password "admin"   -   tenant "apparel", user "cs", password "cs"

  Next
    .\scripts\try.ps1 -Logs          follow the service log      .\scripts\try.ps1 -Down   stop (keeps data)
    .\scripts\try.ps1 -Reset         start again from a clean database
    docs\QUICKSTART.md               what you are looking at, AI keys, the four tenants
    (scripts/demo.sh and scripts/usecases/*.sh are bash: run them from WSL or Git Bash)
"@ | Write-Host

if (-not $NoOpen) { try { Start-Process $Base } catch {} }

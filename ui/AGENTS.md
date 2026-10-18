# AGENTS.md — ui (React console)

React 18 + Vite + TypeScript, the operator console for every backend module. Read the root `AGENTS.md` first. Full
page-by-page contract: `docs/MODULE_NOTES.md` has a "UI contract" section stating the exact shapes
`ui/src/api/types.ts` expects back from each endpoint — treat `types.ts` as the source of truth, not a guess.

## Commands

```bash
npm run dev          # :5173, proxies API calls to :8080 (run the backend separately)
npm run dev:mock      # no backend needed — every page renders against ui/src/api/mock
npm run lint && npm test -- --run && npm run build    # what CI runs; run all three before calling a change done
```

## The pattern every page follows

A route in `App.tsx`, lazy-loaded (`React.lazy`), wrapped in `RequireAuth` + `AppShell` unless it's explicitly
public (`/login`, `/track/:token`). Data fetching is React Query with `refetchInterval` for anything that should
feel live (10-30s depending on the page — there's no WebSocket/SSE in this app, polling is the house pattern, don't
introduce a different one for a new page). An API client lives in `ui/src/api/<area>.ts`; a matching mock lives in
`ui/src/api/mock/<area>.ts` and must be registered in `ui/src/api/mock/index.ts` — `npm run dev:mock` and the test
suite both depend on this, a new endpoint without a mock will work in dev but silently fail every test that
exercises it.

## Design tokens — this distinction is intentional, keep it

Two accent colors: `--accent` (indigo) is the general UI color. `--ai` (teal) is reserved specifically for
autonomy/agentic surfaces — anything representing a self-directed or learned system behavior (the Agentic Control
Tower's `AgenticOps.tsx`, SOO's `LearningConsole.tsx`) uses the `ai` tone/variant on `Badge`/`Button`/nav-link, not
the default indigo. If you're building a new page that lets the system act or learn on its own, use the `ai` tone;
if it's an ordinary operator screen, don't — mixing the two dilutes the one signal this choice is meant to send.
Light/dark theme both come from the existing token system; never hardcode a color.

## Empty and error states aren't optional

Every real page in this app talks to a real backend with real latency and real "nothing here yet" states (a fresh
tenant has no proposals, no experiments, no autonomous actions). Build loading, error, and empty states for every
data fetch, and make the empty state explain *why* it's empty and what will make it populate — not just a blank
panel. This matters more than usual here because a cloned repo's first run is almost always against a freshly
seeded, mostly-empty tenant.

## Tests

Vitest + Testing Library, files under `ui/src/test/` or colocated `*.test.tsx`. Test against the mock API, not a
running backend. If a test fails because data you expected isn't rendered, check the mock's seed data and which
tab/filter is selected by default before assuming the component is broken — a mock proposal seeded in a different
status than the page's default filter is a real, recurring failure mode here.

## Summary

What changes and why. Link the issue or ADR.

## Type of change

- [ ] Bug fix
- [ ] New capability
- [ ] Tenant configuration / examples / docs only
- [ ] Contract change (REST path or body, public Java interface, YAML format, Flyway migration) — needs two approvals

## Checklist

- [ ] `cd backend && mvn -B -ntp verify` passes locally (Postgres on :5440 or Docker available)
- [ ] `cd ui && npm run lint && npm test -- --run && npm run build` passes if the UI changed
- [ ] New or changed behaviour has tests (unit for pure logic; `PostgresTestBase` integration tests for persistence)
- [ ] Javadoc cites the requirement IDs it implements (`FR-*`, `GR-*`, `NFR-*`)
- [ ] `docs/MODULE_NOTES.md` updated for any new endpoint or signature; `docs/CONFIGURATION.md` for any new key
- [ ] No secrets, PII or real customer data in code, tests, fixtures or docs
- [ ] Untrusted text (customer notes, agent instructions, webhook payloads) is treated as data, never as prompt instructions (GR-08)
- [ ] Every model answer stays within enumerated options (GR-04); no new path lets a model act outside its band
- [ ] Commits are signed off (`git commit -s`, DCO)

## How to verify

Commands or clicks a reviewer can follow, with expected output.

## Screenshots

UI changes only.

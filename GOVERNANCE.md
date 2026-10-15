# Governance

Intent OMS is an open-source project licensed under Apache-2.0. This document describes who makes decisions, how, and
how the project ships.

## Roles

| Role | Who | Responsibilities |
|---|---|---|
| Contributor | Anyone who has had a pull request merged | Proposes changes, reviews, triages issues |
| Maintainer | Listed in `MAINTAINERS` (see below) | Reviews and merges, owns one or more modules, cuts releases, enforces the Code of Conduct |
| Security team | A subset of maintainers named in `SECURITY.md` | Receives private vulnerability reports, coordinates fixes and advisories |

### Current maintainers

| GitHub handle | Modules |
|---|---|
| @intentoms-maintainers (team) | all modules until individual module owners are nominated |

Module ownership follows the Maven module list in `docs/ARCHITECTURE.md` §1. A maintainer who owns a module is the
default reviewer for changes under `backend/<module>` and for the matching tenant configuration under `config/`.

### Becoming a maintainer

A contributor with a sustained record (typically three months and several non-trivial merged changes, including reviews
of other people's work) may be nominated by any maintainer. The nomination is a pull request adding the handle to the
table above; it merges with the approval of a majority of existing maintainers and no sustained objection within seven
days. Maintainers who are inactive for six months are moved to an emeritus list by the same process.

## Decision making

* **Day to day**: lazy consensus on pull requests. One maintainer approval merges a change in a module they own; changes
  that cross module boundaries, alter a public Java interface in `docs/ARCHITECTURE.md` §3, change a REST contract in
  `docs/MODULE_NOTES.md`, or add a Flyway migration need a second approval.
* **Architecture decisions**: recorded as ADRs in `docs/adr/` (`ADR-NNN-title.md`, status proposed → accepted →
  superseded). An ADR is accepted after seven days with no unresolved objection from a maintainer.
* **Disagreement**: discussed on the pull request or ADR first. If no consensus emerges within two weeks, maintainers
  vote; a simple majority decides, and the decision and its reasoning are written down in the ADR or issue.
* **Separation of duties** applies to the project as it does to the product: the author of a change does not merge it.

## What we will not accept

* Changes that make any decision layer able to act outside its enumerated options (GR-04), remove a guardrail listed in
  `docs/TRACEABILITY.md` §8, or route untrusted text into a prompt as instructions (GR-08).
* Default secrets, hard-coded credentials, or telemetry that leaves the deployment without an explicit opt-in.
* Vendor lock-in in the decision fabric: providers are implementations of `DecisionProvider`, never special cases in the
  runtime.

## Releases

* Versioning: semantic. `0.x` while the REST contract and YAML formats may still change in minor versions; `1.0` once
  they are frozen behind a deprecation policy (one minor version of warning before removal).
* Cadence: a minor release roughly every six weeks from `main`; patch releases as needed for security and regressions.
* A release is cut by a maintainer: update `CHANGELOG.md`, tag `vX.Y.Z`, let CI build and scan the container image,
  publish the image and the GitHub release notes. Security fixes follow `SECURITY.md`.
* Supported versions are listed in `SECURITY.md`.

## Communication

* GitHub issues and discussions are the primary channels; see `SUPPORT.md`.
* Security reports go to the private channel in `SECURITY.md`, never to a public issue.
* Code of Conduct reports go to conduct@intentoms.ai.

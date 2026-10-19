# Learning cadence (NF-02)

NF-02 asks for online parameter updates (node, lane, bandit posteriors) within 15 minutes of an outcome, analyst and
author runs nightly, and safety stock weekly. This page lists what the code schedules today. Every interval is a
Spring property you can override per deployment. `LearningCadenceTest` (oms-copilot) checks the key rows against the
annotations, so this page cannot drift from the code without a failing test.

| What | How it runs | Default cadence | Property |
|---|---|---|---|
| Node reliability posteriors (FS-02) | `NodeReliabilityLearner`, event subscriber on `order.sourced`, `order.resourced`, `order.delivered` | On the outcome event (outbox relay polls every 250 ms) | `oms.outbox.poll-ms` |
| Lane transit and on-time (FS-03, CX-01) | `LaneDeliveryLearner`, event subscriber on `order.shipped`, `order.delivered` | On the outcome event | `oms.outbox.poll-ms` |
| Customer preferences (CI-01) | `CiPreferenceLearner`, event subscriber | On the event | `oms.outbox.poll-ms` |
| Episodes (OC-02) and outcomes (OC-03) | `EpisodeAssembler`, `OutcomeObserver`, event subscribers | On the event | `oms.outbox.poll-ms` |
| Provisional rewards (OC-04) | `RewardMaturityService` | Every 5 minutes | `oms.learning.provisional-sweep-ms` |
| Outcome coverage (OC-07) | `CoverageMonitor` | Hourly | `oms.learning.coverage-sweep-ms` |
| Auto-promotion (EX-07) and guardrail rollback (EX-05) | `PromotionService`, `GuardrailBreachMonitor` | Every 30 minutes | `oms.learning.auto-promote-sweep-ms`, `oms.learning.guardrail-sweep-ms` |
| Batch learners (DF-01..07, FD-03/04, CI-02) | Each learner's `sweepOnce` | Daily | `oms.learning.df0N.sweep-ms` and similar |
| Agent reputation (FD-05) | `AgentReputationLearner` | Every 6 hours | `oms.learning.agent-reputation-sweep-ms` |
| Analyst (slow loop) | `AnalystService` | Nightly at 02:30 | `oms.learning.analyst-cron` |
| Author (slow loop) | Scheduled author run (DF-04, Wave B F4) | Nightly | see oms-copilot learning package |
| Safety stock proposals (IU-01) | Safety-stock learner (Wave B F3) | Weekly | see oms-learning inventory learners |
| Weekly learning digest (XP-04) | `WeeklyLearningDigestService` | Mondays 07:00 UTC | `oms.learning.digest-cron` |
| Learner metrics gauges (NF-09) | `LearnerMetricsService` | Every minute | `oms.learning.metrics-refresh-ms` |
| Retention (NF-10) | `LearningRetentionService` | Daily 03:30 UTC | `oms.learning.retention.cron` |
| Bandit arm weights (FS-01) | `BanditRewardLearner`: event subscriber on `episode.matured` for `sourcing_policy_bandit` episodes (at most once a minute per tenant), plus a sweep for provisional rewards | On the matured episode; sweep every 10 minutes, so a provisional reward (5-minute reward sweep) reaches the weights within 15 minutes | `oms.learning.fs01.sweep-ms` |

package ai.intentoms.copilot.bootstrap;

import ai.intentoms.copilot.change.ProposedChange;

import java.util.List;
import java.util.Map;

/**
 * The configuration bundle proposed for a new tenant, rendered as the same artefact kinds as any ChangeSet so it flows
 * through propose → stage → apply. {@code sections} summarises the bundle for the UI (order types, channel defaults,
 * margin floor, returns, compliance, SLAs, queues, decisions, node skeleton, state model variants).
 */
public record TenantBlueprint(String summary, List<String> assumptions, List<String> questions, Map<String, Object> sections, List<ProposedChange> changes, boolean generated) {}

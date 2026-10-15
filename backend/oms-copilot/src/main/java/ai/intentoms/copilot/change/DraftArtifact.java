package ai.intentoms.copilot.change;

import java.time.Instant;
import java.util.Map;

/**
 * A staged artefact whose owning module has no draft state (state models, order types, SLAs, playbooks, compliance rules,
 * tenant config). It stays a draft until an authorised person calls {@code apply} (FR-CFG-02, GR-14).
 * Status: {@code draft} | {@code applied} | {@code rejected}.
 */
public record DraftArtifact(String id, String changesetId, String changeId, ChangeKind kind, String target, String content, String summary, String status,
                            String createdBy, Instant createdAt, String appliedBy, Instant appliedAt, String applyReason, Map<String, Object> result) {}

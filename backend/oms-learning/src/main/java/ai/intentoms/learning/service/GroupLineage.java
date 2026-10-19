package ai.intentoms.learning.service;

import ai.intentoms.learning.model.Episode;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OC-02 (audit A-N1): which decision planned a fulfilment group, read from the ledger. Every sourcing run appends
 * {@code order.sourced} with the groups it created ({@code fulfilmentGroupIds}) and the decisions that shaped the plan
 * ({@code decisionRecordId}, plus {@code decisionRecordIds} for the split check). A group's outcomes belong to the
 * decision point that planned it: a re-sourcing decision (its episode keyed by the group it replaced) receives the
 * outcomes of the replacement groups, and the original order-level choice, whose group failed, does not get the credit.
 */
@Component
public class GroupLineage {
    private final JdbcClient jdbc;

    public GroupLineage(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** The decision records that planned this group of the order (empty when no sourcing run names it). */
    public Set<String> plannedBy(String orderId, String groupId) {
        Set<String> out = new LinkedHashSet<>();
        if (orderId == null || groupId == null) return out;
        List<String> payloads = jdbc.sql("select payload::text from event where tenant_id = :t and aggregate_type = 'order' and aggregate_id = :o and type = 'order.sourced' order by sequence")
            .param("t", TenantContext.require()).param("o", orderId).query(String.class).list();
        for (String json : payloads) {
            Map<String, Object> p = Json.readMap(json);
            if (!(p.get("fulfilmentGroupIds") instanceof Collection<?> groups) || !groups.contains(groupId)) continue;
            if (p.get("decisionRecordId") != null && !String.valueOf(p.get("decisionRecordId")).isBlank()) out.add(String.valueOf(p.get("decisionRecordId")));
            if (p.get("decisionRecordIds") instanceof Collection<?> ids) ids.forEach(id -> { if (id != null && !String.valueOf(id).isBlank()) out.add(String.valueOf(id)); });
        }
        return out;
    }

    /**
     * For an outcome naming one fulfilment group: episodes whose decision planned the group map to true, the other
     * episodes of those same decisions map to false (a sibling decision point of the same decision did not choose this
     * group), and every other episode is absent (the usual group and line rules apply to it).
     */
    public Map<String, Boolean> owners(String orderId, Object groupId, Collection<Episode> episodes) {
        if (groupId == null || orderId == null || episodes.isEmpty()) return Map.of();
        Set<String> planners = plannedBy(orderId, String.valueOf(groupId));
        if (planners.isEmpty()) return Map.of();
        Map<String, Boolean> out = new HashMap<>();
        Set<String> decisions = new HashSet<>();
        for (Episode ep : episodes) {
            if (planners.contains(ep.decisionId()) || (ep.lastDecisionId() != null && planners.contains(ep.lastDecisionId()))) {
                out.put(ep.episodeId(), true);
                decisions.add(ep.decisionName());
            }
        }
        for (Episode ep : episodes) if (!out.containsKey(ep.episodeId()) && decisions.contains(ep.decisionName())) out.put(ep.episodeId(), false);
        return out;
    }
}

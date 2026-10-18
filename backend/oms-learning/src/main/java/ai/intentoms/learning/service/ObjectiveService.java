package ai.intentoms.learning.service;

import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.learning.model.ObjectiveProfile;
import ai.intentoms.learning.model.ObjectiveWeights;
import ai.intentoms.learning.store.ObjectiveProfileRepository;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * XP-07 and the never-autonomous rule for the objective: a change to the reward weights is always a DRAFT first
 * (typed by a person, or drafted from plain English by the copilot) and becomes live only when a different person
 * approves it. Both steps are ledger events (GV-04).
 */
@Service
public class ObjectiveService {
    public static final double MAX_WEIGHT = 5.0;

    private final ObjectiveProfileRepository objectives;
    private final LearningEvents events;

    public ObjectiveService(ObjectiveProfileRepository objectives, LearningEvents events) {
        this.objectives = objectives;
        this.events = events;
    }

    @Transactional
    public ObjectiveProfile draft(ObjectiveWeights weights, Map<String, Object> constraints, String by, String sourceText, String method, Map<String, String> rationale) {
        validate(weights);
        int v = objectives.nextVersion();
        ObjectiveProfile p = new ObjectiveProfile(v, ObjectiveProfile.Status.DRAFT, weights, constraints == null ? Map.of() : constraints, null, null, by, Instant.now(),
            sourceText, method == null ? "manual" : method, rationale == null ? Map.of() : rationale);
        objectives.insert(p);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", v);
        payload.put("weights", weights);
        payload.put("method", p.draftMethod());
        payload.put("sourceText", sourceText);
        events.objectiveDrafted(v, Actor.parse(by), payload);
        return p;
    }

    @Transactional
    public ObjectiveProfile approve(int version, String by) {
        ObjectiveProfile p = objectives.find(version).orElseThrow(() -> new NotFoundException("Objective profile version " + version + " not found"));
        if (p.status() != ObjectiveProfile.Status.DRAFT) throw new ConflictException("Objective profile version " + version + " is " + p.status() + ", not a draft");
        if (by.equals(p.createdBy())) throw new ValidationException("The person who drafted an objective profile cannot approve it; ask another approver");
        ObjectiveProfile previous = objectives.live().orElse(null);
        objectives.approve(version, by, Instant.now());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", version);
        payload.put("weights", p.weights());
        payload.put("draftedBy", p.createdBy());
        payload.put("previousVersion", previous == null ? null : previous.version());
        events.objectiveApproved(version, Actor.parse(by), payload);
        return objectives.find(version).orElseThrow();
    }

    public List<ObjectiveProfile> drafts() { return objectives.drafts(); }

    public List<ObjectiveProfile> history(int limit) { return objectives.history(limit); }

    static void validate(ObjectiveWeights w) {
        if (w == null) throw new ValidationException("weights is required");
        double[] all = {w.fill(), w.otd(), w.cost(), w.split(), w.fraud(), w.cx(), w.inv()};
        double sum = 0;
        for (double x : all) {
            if (Double.isNaN(x) || x < 0 || x > MAX_WEIGHT) throw new ValidationException("Every weight must be between 0 and " + MAX_WEIGHT);
            sum += x;
        }
        if (sum == 0) throw new ValidationException("At least one weight must be above zero");
    }
}

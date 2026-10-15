package ai.intentoms.decision.api;

import ai.intentoms.decision.definition.GoldenCaseRepository;
import ai.intentoms.decision.model.GoldenCase;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Golden set upload and inspection (GR-11). */
@RestController
@RequestMapping("/api/v1/golden-sets")
public class GoldenSetController {
    private final GoldenCaseRepository golden;

    public GoldenSetController(GoldenCaseRepository golden) { this.golden = golden; }

    public record CaseUpload(String caseId, Map<String, Object> input, Map<String, Object> expected, String notes) {}

    @GetMapping
    @RequiresPermission(Permissions.DECISION_READ)
    public List<String> sets() { return golden.sets(); }

    @GetMapping("/{name}")
    @RequiresPermission(Permissions.DECISION_READ)
    public List<GoldenCase> cases(@PathVariable String name) { return golden.forSet(name); }

    /** Upserts cases into the set (existing case ids are replaced). */
    @PutMapping("/{name}")
    @RequiresPermission(Permissions.DECISION_EDIT)
    public Map<String, Object> upload(@PathVariable String name, @RequestBody List<CaseUpload> cases) {
        int i = 0;
        for (CaseUpload c : cases) {
            String id = c.caseId() == null || c.caseId().isBlank() ? name + "_" + (++i) : c.caseId();
            golden.upsert(new GoldenCase(name, id, c.input() == null ? Map.of() : c.input(), c.expected() == null ? Map.of() : c.expected(), c.notes()));
        }
        return Map.of("goldenSet", name, "cases", golden.count(name));
    }

    @DeleteMapping("/{name}")
    @RequiresPermission(Permissions.DECISION_EDIT)
    public Map<String, Object> delete(@PathVariable String name) { return Map.of("goldenSet", name, "deleted", golden.delete(name)); }
}

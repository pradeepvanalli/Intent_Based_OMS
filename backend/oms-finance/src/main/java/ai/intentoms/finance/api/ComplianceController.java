package ai.intentoms.finance.api;

import ai.intentoms.finance.compliance.ComplianceInput;
import ai.intentoms.finance.compliance.ComplianceRule;
import ai.intentoms.finance.compliance.ComplianceRuleLoader;
import ai.intentoms.finance.compliance.ComplianceService;
import ai.intentoms.finance.compliance.ComplianceViolation;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** L0 compliance checks and rule management (FR-FIN-03, FR-CAP-05). */
@RestController
@RequestMapping("/api/v1/compliance")
public class ComplianceController {
    private final ComplianceService compliance;
    private final ComplianceRuleLoader loader;

    public ComplianceController(ComplianceService compliance, ComplianceRuleLoader loader) {
        this.compliance = compliance;
        this.loader = loader;
    }

    public record CheckResponse(boolean compliant, boolean blocked, List<ComplianceViolation> violations) {}

    @PostMapping("/check")
    @RequiresPermission(Permissions.ORDER_READ)
    public CheckResponse check(@RequestBody ComplianceInput in) {
        if (in.lines() == null) throw new ValidationException("lines are required");
        List<ComplianceViolation> v = compliance.check(in);
        return new CheckResponse(v.isEmpty(), v.stream().anyMatch(ComplianceViolation::blocks), v);
    }

    @GetMapping("/rules")
    @RequiresPermission({Permissions.ORDER_READ, Permissions.CONFIG_ADMIN})
    public List<ComplianceRule> rules() { return compliance.rules(); }

    /** Replaces / adds rules from a YAML document ({@code {"yaml": "rules: ..."}}); validated as a whole before writing. */
    @PutMapping("/rules")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public List<ComplianceRule> load(@RequestBody Map<String, String> body) {
        String yaml = body.get("yaml");
        if (yaml == null || yaml.isBlank()) throw new ValidationException("body.yaml is required");
        return loader.loadYaml(yaml, "api");
    }
}

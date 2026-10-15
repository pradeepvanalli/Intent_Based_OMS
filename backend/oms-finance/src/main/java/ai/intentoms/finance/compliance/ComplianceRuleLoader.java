package ai.intentoms.finance.compliance;

import ai.intentoms.platform.audit.AuditLog;
import ai.intentoms.platform.cel.CelEngine;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads compliance rules from YAML ({@code config/tenants/<t>/rules/compliance.yaml}) into {@code compliance_rule}
 * for the current tenant (FR-FIN-03). Format:
 * <pre>
 * rules:
 *   - id: hazmat_no_air
 *     name: Hazmat may not ship by air
 *     scope: line            # line | order (default line)
 *     when: line.hazmat && order.ship_method.startsWith("air")
 *     action: block          # block | hold | require_signature
 *     reason_code: HAZMAT_AIR
 *     severity: error        # error | warning
 *     message: "{sku} is hazmat and cannot ship via {shipMethod}"
 *     priority: 10
 *     active: true
 * </pre>
 * Every expression is validated with {@link CelEngine#validate} before anything is written; a single bad rule
 * rejects the whole file so a tenant never ends up with a partially applied policy.
 */
@Service
public class ComplianceRuleLoader {
    private static final Logger log = LoggerFactory.getLogger(ComplianceRuleLoader.class);
    private final ComplianceRuleRepository repo;
    private final CelEngine cel;
    private final AuditLog audit;
    private final DefaultComplianceService cacheOwner;

    public ComplianceRuleLoader(ComplianceRuleRepository repo, CelEngine cel, AuditLog audit, DefaultComplianceService cacheOwner) {
        this.repo = repo;
        this.cel = cel;
        this.audit = audit;
        this.cacheOwner = cacheOwner;
    }

    /** Parses and upserts every rule in the file for the current tenant; returns the rules written. */
    public List<ComplianceRule> loadFile(Path file) {
        try {
            return loadYaml(Files.readString(file), file.toString());
        } catch (IOException e) {
            throw new ValidationException("Cannot read compliance rules file " + file + ": " + e.getMessage());
        }
    }

    @Transactional
    public List<ComplianceRule> loadYaml(String yaml, String source) {
        List<ComplianceRule> rules = parse(yaml);
        for (ComplianceRule r : rules) repo.upsert(r);
        cacheOwner.invalidate();
        audit.record("compliance.rules_loaded", "compliance_rule", source, "Loaded " + rules.size() + " rules", Map.of("ruleIds", rules.stream().map(ComplianceRule::id).toList()));
        log.info("Loaded {} compliance rules from {}", rules.size(), source);
        return rules;
    }

    /** Parses and validates without persisting (used by static checks and the loader itself). */
    public List<ComplianceRule> parse(String yaml) {
        Map<String, Object> doc = Json.yamlMap(yaml);
        Object rulesNode = doc.get("rules");
        if (!(rulesNode instanceof List<?> list)) throw new ValidationException("compliance.yaml must contain a top-level 'rules' list");
        List<ComplianceRule> out = new ArrayList<>(list.size());
        List<String> problems = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) { problems.add("rule entries must be maps"); continue; }
            ComplianceRule r = fromMap(m);
            List<String> issues = cel.validate(r.when());
            if (!issues.isEmpty()) problems.add("rule '" + r.id() + "': " + String.join("; ", issues));
            out.add(r);
        }
        if (!problems.isEmpty()) throw new ValidationException("Invalid compliance rules", Map.of("problems", problems));
        return out;
    }

    /** Builds a rule from a YAML / JSON map (also used for {@code tenant_config compliance.rules} entries). */
    public static ComplianceRule fromMap(Map<?, ?> m) {
        Object prio = m.get("priority");
        Object active = m.get("active");
        return new ComplianceRule(
            str(m.get("id")), str(m.get("name")), str(m.get("scope")), str(m.get("when")), str(m.get("action")),
            str(m.get("reason_code") != null ? m.get("reason_code") : m.get("reasonCode")), str(m.get("severity")), str(m.get("message")),
            prio instanceof Number n ? n.intValue() : prio == null ? 100 : Integer.parseInt(prio.toString()),
            active == null || Boolean.parseBoolean(active.toString()));
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}

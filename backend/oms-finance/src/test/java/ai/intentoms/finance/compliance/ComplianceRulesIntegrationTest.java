package ai.intentoms.finance.compliance;

import ai.intentoms.finance.FinanceTestBase;
import ai.intentoms.finance.config.FinanceConfigKeys;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-FIN-03 / FR-CAP-05: every demo rule fires on its scenario and a clean order passes. */
class ComplianceRulesIntegrationTest extends FinanceTestBase {
    static final Path DEMO_RULES = Paths.get(System.getProperty("user.dir")).resolve("../../config/tenants/demo/rules/compliance.yaml").normalize();

    @Autowired ComplianceService compliance;
    @Autowired ComplianceRuleLoader loader;
    @Autowired DefaultComplianceService impl;
    @Autowired TenantConfigStore config;

    @BeforeEach
    void loadDemoRules() {
        List<ComplianceRule> rules = loader.loadFile(DEMO_RULES);
        assertThat(rules).hasSizeGreaterThanOrEqualTo(11);
        impl.invalidate();
    }

    private static ComplianceLine line(String sku, Map<String, Object> attrs) { return new ComplianceLine("L-" + sku, sku, 1, attrs); }

    private List<ComplianceViolation> check(ComplianceLine line, ComplianceDestination dest, ComplianceCustomer customer, String ship) {
        return compliance.check(new ComplianceInput(List.of(line), dest, customer, ship));
    }

    private static final ComplianceDestination CA = new ComplianceDestination("CA", "94105", "US", "residential");
    private static final ComplianceCustomer VERIFIED_ADULT = new ComplianceCustomer(true, 34);

    @Test
    void cleanOrderPasses() {
        assertThat(check(line("MUG", Map.of("category", "home")), CA, VERIFIED_ADULT, "ground")).isEmpty();
        assertThat(check(line("MUG", Map.of()), CA, ComplianceCustomer.unverified(), "air_2day")).isEmpty();
    }

    @Test
    void hazmatCannotFlyButNeedsSignatureOnGround() {
        List<ComplianceViolation> air = check(line("AERO", Map.of("hazmat", true)), CA, VERIFIED_ADULT, "air_2day");
        assertThat(air).extracting(ComplianceViolation::reasonCode).containsExactly("HAZMAT_AIR");
        assertThat(air.get(0).action()).isEqualTo("block");
        assertThat(air.get(0).lineId()).isEqualTo("L-AERO");
        assertThat(air.get(0).message()).contains("AERO").contains("air_2day");
        List<ComplianceViolation> ground = check(line("AERO", Map.of("hazmat", true)), CA, VERIFIED_ADULT, "ground");
        assertThat(ground).extracting(ComplianceViolation::reasonCode).containsExactly("HAZMAT_SIGNATURE");
        assertThat(ground.get(0).action()).isEqualTo("require_signature");
        assertThat(ground.get(0).blocks()).isFalse();
    }

    @Test
    void alcoholRestrictedStatesAndTenantOverride() {
        ComplianceDestination utah = new ComplianceDestination("UT", "84101", "US");
        List<ComplianceViolation> v = check(line("WINE", Map.of("category", "alcohol", "age_restricted", true, "min_age", 21)), utah, VERIFIED_ADULT, "ground");
        assertThat(v).extracting(ComplianceViolation::reasonCode).containsExactlyInAnyOrder("ALCOHOL_STATE_RESTRICTED", "ALCOHOL_ADULT_SIGNATURE");
        // California only requires the adult signature.
        assertThat(check(line("WINE", Map.of("category", "alcohol")), CA, VERIFIED_ADULT, "ground"))
            .extracting(ComplianceViolation::reasonCode).containsExactly("ALCOHOL_ADULT_SIGNATURE");
        // Tenant adds Texas via tenant_config compliance.restricted_states.
        config.set(FinanceConfigKeys.COMPLIANCE_RESTRICTED_STATES, Map.of("alcohol", List.of("TX")));
        assertThat(check(line("WINE", Map.of("category", "alcohol")), new ComplianceDestination("TX", "73301", "US"), VERIFIED_ADULT, "ground"))
            .extracting(ComplianceViolation::reasonCode).contains("ALCOHOL_STATE_RESTRICTED");
    }

    @Test
    void tobaccoRestrictedStates() {
        assertThat(check(line("VAPE1", Map.of("category", "vape")), new ComplianceDestination("NY", "10001", "US"), VERIFIED_ADULT, "ground"))
            .extracting(ComplianceViolation::reasonCode).containsExactly("TOBACCO_STATE_RESTRICTED");
        assertThat(check(line("VAPE1", Map.of("category", "vape")), CA, VERIFIED_ADULT, "ground")).isEmpty();
    }

    @Test
    void ageRestrictedGoods() {
        List<ComplianceViolation> unverified = check(line("KNIFE", Map.of("age_restricted", true, "min_age", 18)), CA, ComplianceCustomer.unverified(), "ground");
        assertThat(unverified).extracting(ComplianceViolation::reasonCode).containsExactly("AGE_VERIFICATION_REQUIRED");
        assertThat(unverified.get(0).action()).isEqualTo("hold");
        List<ComplianceViolation> tooYoung = check(line("KNIFE", Map.of("age_restricted", true, "min_age", 18)), CA, new ComplianceCustomer(true, 16), "ground");
        assertThat(tooYoung).extracting(ComplianceViolation::reasonCode).containsExactly("UNDER_MINIMUM_AGE");
        assertThat(check(line("KNIFE", Map.of("age_restricted", true, "min_age", 18)), CA, VERIFIED_ADULT, "ground")).isEmpty();
    }

    @Test
    void lithiumBatteries() {
        ComplianceDestination poBox = new ComplianceDestination("CA", "94105", "US", "po_box");
        assertThat(check(line("PWR", Map.of("lithium_battery", true)), poBox, VERIFIED_ADULT, "ground"))
            .extracting(ComplianceViolation::reasonCode).containsExactly("LITHIUM_PO_BOX");
        // APO/FPO ZIP pattern also counts as a PO box.
        assertThat(check(line("PWR", Map.of("lithium_battery", true)), new ComplianceDestination("AE", "09096", "US"), VERIFIED_ADULT, "ground"))
            .extracting(ComplianceViolation::reasonCode).containsExactly("LITHIUM_PO_BOX");
        // Standalone batteries are ground-only; devices containing batteries may fly.
        assertThat(check(line("CELL", Map.of("lithium_battery", true, "category", "battery")), CA, VERIFIED_ADULT, "air_overnight"))
            .extracting(ComplianceViolation::reasonCode).containsExactly("LITHIUM_AIR");
        assertThat(check(line("LAPTOP", Map.of("lithium_battery", true, "category", "electronics")), CA, VERIFIED_ADULT, "air_overnight")).isEmpty();
    }

    @Test
    void exportControlledItemsStayInTheUs() {
        assertThat(check(line("GPU", Map.of("export_controlled", true)), new ComplianceDestination("ON", "M5V", "CA"), VERIFIED_ADULT, "international_air"))
            .extracting(ComplianceViolation::reasonCode).containsExactly("EXPORT_CONTROLLED");
        assertThat(check(line("GPU", Map.of("export_controlled", true)), CA, VERIFIED_ADULT, "ground")).isEmpty();
    }

    @Test
    void restrictedZipIsAnOrderLevelHold() {
        config.set(FinanceConfigKeys.COMPLIANCE_RESTRICTED_ZIPS, List.of("99501", 10007));
        List<ComplianceViolation> v = check(line("MUG", Map.of()), new ComplianceDestination("AK", "99501-1234", "US"), VERIFIED_ADULT, "ground");
        assertThat(v).hasSize(1);
        assertThat(v.get(0).reasonCode()).isEqualTo("ZIP_RESTRICTED");
        assertThat(v.get(0).action()).isEqualTo("hold");
        assertThat(v.get(0).lineId()).isNull();
        assertThat(v.get(0).message()).contains("99501-1234");
        assertThat(check(line("MUG", Map.of()), new ComplianceDestination("NY", "10007", "US"), VERIFIED_ADULT, "ground")).hasSize(1);
        assertThat(check(line("MUG", Map.of()), CA, VERIFIED_ADULT, "ground")).isEmpty();
    }

    @Test
    void multiLineOrderReportsPerLine() {
        var in = new ComplianceInput(List.of(line("AERO", Map.of("hazmat", true)), line("MUG", Map.of()), line("GPU", Map.of("export_controlled", true))),
            new ComplianceDestination("BC", "V6B", "CA"), VERIFIED_ADULT, "international_air");
        List<ComplianceViolation> v = compliance.check(in);
        assertThat(v).extracting(ComplianceViolation::lineId, ComplianceViolation::reasonCode)
            .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("L-AERO", "HAZMAT_AIR"), org.assertj.core.groups.Tuple.tuple("L-GPU", "EXPORT_CONTROLLED"));
    }

    @Test
    void tenantConfigRulesMergeAndBrokenRulesHoldInsteadOfPassing() {
        config.set(FinanceConfigKeys.COMPLIANCE_RULES, List.of(
            Map.of("id", "no_big_qty", "when", "line.qty > 5", "action", "hold", "reason_code", "QTY_LIMIT", "message", "Too many of {sku}"),
            Map.of("id", "broken", "when", "line.nonexistent_attr == true", "action", "block")));
        impl.invalidate();
        List<ComplianceViolation> v = compliance.check(new ComplianceInput(List.of(new ComplianceLine("L1", "MUG", 6, Map.of())), CA, VERIFIED_ADULT, "ground"));
        assertThat(v).extracting(ComplianceViolation::reasonCode).containsExactlyInAnyOrder("QTY_LIMIT", "RULE_ERROR");
        assertThat(v.stream().filter(x -> x.reasonCode().equals("RULE_ERROR")).findFirst().orElseThrow().action()).isEqualTo("hold");
    }

    @Test
    void loaderRejectsInvalidCelAtomically() {
        int before = compliance.rules().size();
        assertThatThrownBy(() -> loader.loadYaml("rules:\n  - id: ok\n    when: line.hazmat == true\n  - id: bad\n    when: line.hazmat ==\n", "test"))
            .isInstanceOf(ValidationException.class);
        impl.invalidate();
        assertThat(compliance.rules()).hasSize(before);
        assertThatThrownBy(() -> loader.parse("rules:\n  - id: x\n    when: 'true'\n    action: delete\n")).isInstanceOf(ValidationException.class);
    }
}

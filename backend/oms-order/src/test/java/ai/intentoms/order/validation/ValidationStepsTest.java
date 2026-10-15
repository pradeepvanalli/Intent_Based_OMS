package ai.intentoms.order.validation;

import ai.intentoms.finance.compliance.ComplianceInput;
import ai.intentoms.finance.compliance.ComplianceRule;
import ai.intentoms.finance.compliance.ComplianceService;
import ai.intentoms.finance.compliance.ComplianceViolation;
import ai.intentoms.finance.model.Money;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.validation.steps.AddressValidationStep;
import ai.intentoms.order.validation.steps.AgentProvenanceStep;
import ai.intentoms.order.validation.steps.ComplianceStep;
import ai.intentoms.order.validation.steps.ItemPriceSanityStep;
import ai.intentoms.order.validation.steps.PaymentStatusStep;
import ai.intentoms.order.validation.steps.SchemaValidationStep;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static ai.intentoms.order.validation.TestRequests.*;
import static org.assertj.core.api.Assertions.assertThat;

/** FR-CAP-05: every built-in step's block and warn paths, without Spring. */
class ValidationStepsTest {

    // ---- schema -------------------------------------------------------------------------------------------------
    @Test
    void schemaBlocksStructuralProblems() {
        CaptureRequest bad = new CaptureRequest(null, "", null, null, null,
            List.of(new CaptureRequest.LineInput("L1", null, null, 0, Money.usd("-1"), null, null, null), line("L1", "TEE-BLK-M", 1, "1.00"),
                new CaptureRequest.LineInput("L2", "X", null, 1, Money.of("1.00", "EUR"), null, null, null)),
            List.of(new CaptureRequest.AddressInput("home", null, null, null, null, null, null, null, null, null)),
            List.of(), List.of(new CaptureRequest.TenderInput(null, null, null, null)), List.of(), List.of(), null, null, "USD", Map.of(), null);
        List<ValidationIssue> issues = new SchemaValidationStep().validate(bad, ctx(bad, catalog()));
        assertThat(issues).allMatch(ValidationIssue::blocks);
        assertThat(issues).extracting(ValidationIssue::code).contains("channel_required", "channel_order_id_required", "sku_required", "qty_invalid", "unit_price_negative",
            "line_id_duplicate", "currency_mismatch", "address_type_unknown", "tender_type_required", "tender_amount_required");
    }

    @Test
    void schemaPassesCleanRequestAndValidatesExtensionsAgainstTenantSchema() {
        CaptureRequest ok = simple();
        assertThat(new SchemaValidationStep().validate(ok, ctx(ok, catalog()))).isEmpty();
        Map<String, Object> schema = Map.of("type", "object", "required", List.of("gift_message"), "properties", Map.of("gift_message", Map.of("type", "string", "maxLength", 5)), "additionalProperties", false);
        CaptureRequest withExt = new CaptureRequest("web", "W-2", null, "c", null, List.of(line("L1", "TEE-BLK-M", 1, "28.00")), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, "USD",
            Map.of("gift_message", "much too long", "rogue", 1), null);
        ValidationContext ctx = new ValidationContext(STANDARD, catalog(), Money.usd("28.00"), false, 0.25, schema);
        List<ValidationIssue> issues = new SchemaValidationStep().validate(withExt, ctx);
        assertThat(issues).extracting(ValidationIssue::code).containsOnly("extension_schema");
        assertThat(issues).hasSize(2);
        assertThat(issues).allMatch(ValidationIssue::blocks);
    }

    // ---- item_price_sanity --------------------------------------------------------------------------------------
    @Test
    void priceSanityBlocksUnknownSkuAndImplausiblePriceWarnsOnDeviation() {
        ItemPriceSanityStep step = new ItemPriceSanityStep();
        CaptureRequest unknown = request(List.of(line("L1", "NOPE", 1, "10.00")), List.of(), List.of());
        assertThat(step.validate(unknown, ctx(unknown, catalog()))).singleElement().satisfies(i -> { assertThat(i.code()).isEqualTo("unknown_sku"); assertThat(i.blocks()).isTrue(); });
        assertThat(step.validate(unknown, new ValidationContext(STANDARD, catalog(), Money.usd("10"), true, 0.25, null))).singleElement().satisfies(i -> { assertThat(i.code()).isEqualTo("unknown_sku"); assertThat(i.blocks()).isFalse(); });

        CaptureRequest exact = request(List.of(line("L1", "TEE-BLK-M", 1, "28.00")), List.of(), List.of());
        assertThat(step.validate(exact, ctx(exact, catalog()))).isEmpty();
        CaptureRequest within = request(List.of(line("L1", "TEE-BLK-M", 1, "24.00")), List.of(), List.of());
        assertThat(step.validate(within, ctx(within, catalog()))).isEmpty();
        CaptureRequest deviating = request(List.of(line("L1", "TEE-BLK-M", 1, "40.00")), List.of(), List.of());
        assertThat(step.validate(deviating, ctx(deviating, catalog()))).singleElement().satisfies(i -> { assertThat(i.code()).isEqualTo("price_out_of_tolerance"); assertThat(i.blocks()).isFalse(); });
        CaptureRequest absurd = request(List.of(line("L1", "TEE-BLK-M", 1, "60.00")), List.of(), List.of());
        assertThat(step.validate(absurd, ctx(absurd, catalog()))).singleElement().satisfies(i -> { assertThat(i.code()).isEqualTo("price_implausible"); assertThat(i.blocks()).isTrue(); });
        CaptureRequest free = request(List.of(line("L1", "TEE-BLK-M", 1, "0.00")), List.of(), List.of());
        assertThat(step.validate(free, ctx(free, catalog()))).singleElement().satisfies(i -> assertThat(i.code()).isEqualTo("price_implausible"));
    }

    // ---- address ------------------------------------------------------------------------------------------------
    @Test
    void addressStepVerifiesUsAddressesFlagsPoBoxesAndBlocksUndeliverable() {
        AddressValidationStep step = new AddressValidationStep(new MockAddressVerifier());
        CaptureRequest ok = simple();
        ValidationContext ctx = ctx(ok, catalog());
        assertThat(step.validate(ok, ctx)).isEmpty();
        assertThat(ctx.addressResults().get("ship_to").verified()).isTrue();
        assertThat(ctx.addressResults().get("ship_to").addressType()).isEqualTo("residential");

        CaptureRequest mismatch = request(List.of(line("L1", "TEE-BLK-M", 1, "28.00")), List.of(shipTo("1 Main St", "Oakland", "CA", "10001")), List.of());
        assertThat(step.validate(mismatch, ctx(mismatch, catalog()))).singleElement().satisfies(i -> { assertThat(i.code()).isEqualTo("address_invalid"); assertThat(i.message()).contains("does not belong to CA"); });
        CaptureRequest badState = request(List.of(line("L1", "TEE-BLK-M", 1, "28.00")), List.of(shipTo("1 Main St", "Nowhere", "ZZ", "1234")), List.of());
        assertThat(step.validate(badState, ctx(badState, catalog()))).singleElement().satisfies(i -> { assertThat(i.code()).isEqualTo("address_invalid"); assertThat(i.message()).contains("not a US state").contains("5 digits"); });

        CaptureRequest undeliverable = request(List.of(line("L1", "TEE-BLK-M", 1, "28.00")), List.of(shipTo("UNDELIVERABLE 1 Main St", "Oakland", "CA", "94607")), List.of());
        assertThat(step.validate(undeliverable, ctx(undeliverable, catalog()))).singleElement().satisfies(i -> { assertThat(i.code()).isEqualTo("address_undeliverable"); assertThat(i.blocks()).isTrue(); });

        CaptureRequest poBox = request(List.of(line("L1", "TEE-BLK-M", 1, "28.00")), List.of(shipTo("P.O. Box 123", "Oakland", "CA", "94607")), List.of());
        ValidationContext poCtx = ctx(poBox, catalog());
        assertThat(step.validate(poBox, poCtx)).singleElement().satisfies(i -> { assertThat(i.code()).isEqualTo("po_box"); assertThat(i.blocks()).isFalse(); });
        assertThat(poCtx.addressResults().get("ship_to").addressType()).isEqualTo("po_box");

        CaptureRequest missing = request(List.of(line("L1", "TEE-BLK-M", 1, "28.00")), List.of(), List.of());
        assertThat(step.validate(missing, ctx(missing, catalog()))).extracting(ValidationIssue::code).containsExactly("ship_to_required");
        CaptureRequest digital = request(List.of(line("L1", "GIFT-CARD", 1, "50.00")), List.of(), List.of());
        assertThat(step.validate(digital, ctx(digital, catalog()))).isEmpty();

        CaptureRequest abroad = request(List.of(line("L1", "TEE-BLK-M", 1, "28.00")), List.of(new CaptureRequest.AddressInput("ship_to", "A", "1 Rue", null, "Paris", null, "75001", "FR", null, null)), List.of());
        assertThat(step.validate(abroad, ctx(abroad, catalog()))).singleElement().satisfies(i -> { assertThat(i.code()).isEqualTo("address_unverified"); assertThat(i.blocks()).isFalse(); });
    }

    // ---- payment_status -----------------------------------------------------------------------------------------
    @Test
    void paymentStatusRequiresCoveringTendersUnlessDeferred() {
        PaymentStatusStep step = new PaymentStatusStep();
        CaptureRequest none = request(List.of(line("L1", "TEE-BLK-M", 2, "28.00")), List.of(), List.of());
        assertThat(step.validate(none, ctx(none, catalog()))).extracting(ValidationIssue::code).containsExactly("tender_required");
        CaptureRequest short_ = request(List.of(line("L1", "TEE-BLK-M", 2, "28.00")), List.of(), List.of(card("20.00")));
        assertThat(step.validate(short_, ctx(short_, catalog()))).extracting(ValidationIssue::code).containsExactly("tender_insufficient");
        CaptureRequest exact = request(List.of(line("L1", "TEE-BLK-M", 2, "28.00")), List.of(), List.of(card("56.00")));
        assertThat(step.validate(exact, ctx(exact, catalog()))).isEmpty();
        CaptureRequest netTerms = request(List.of(line("L1", "TEE-BLK-M", 2, "28.00")), List.of(), List.of(new CaptureRequest.TenderInput("net_terms", null, Money.usd("0"), null)));
        assertThat(step.validate(netTerms, ctx(netTerms, catalog()))).isEmpty();
        CaptureRequest agentToken = request(List.of(line("L1", "TEE-BLK-M", 2, "28.00")), List.of(), List.of(new CaptureRequest.TenderInput("agent_token", "mandate-7", Money.usd("10"), null)));
        assertThat(step.validate(agentToken, ctx(agentToken, catalog()))).isEmpty();
        CaptureRequest noToken = request(List.of(line("L1", "TEE-BLK-M", 2, "28.00")), List.of(), List.of(new CaptureRequest.TenderInput("card", null, Money.usd("100"), null)));
        assertThat(step.validate(noToken, ctx(noToken, catalog()))).extracting(ValidationIssue::code).containsExactly("tender_token_required");
        // Expected total from the context (incl. tax) wins over the raw merchandise sum.
        ValidationContext taxed = new ValidationContext(STANDARD, catalog(), Money.usd("60.06"), false, 0.25, null);
        assertThat(step.validate(exact, taxed)).extracting(ValidationIssue::code).containsExactly("tender_insufficient");
    }

    // ---- compliance ---------------------------------------------------------------------------------------------
    @Test
    void complianceMapsBlockHoldAndSignatureViolations() {
        ComplianceService fake = new ComplianceService() {
            @Override public List<ComplianceViolation> check(ComplianceInput in) {
                assertThat(in.shipMethod()).isEqualTo("ground");
                assertThat(in.destination().state()).isEqualTo("CA");
                assertThat(in.lines()).singleElement().satisfies(l -> {
                    assertThat(l.attributes().get("hazmat")).isEqualTo(true);
                    assertThat(l.attributes()).containsKey("lithium_battery");
                });
                return List.of(new ComplianceViolation("hazmat_no_air", "block", "HAZMAT_AIR", "L1", "no air", "error"),
                    new ComplianceViolation("age", "hold", "AGE_VERIFICATION_REQUIRED", "L1", "verify age", "error"),
                    new ComplianceViolation("sig", "require_signature", "HAZMAT_SIGNATURE", "L1", "sign", "warning"));
            }
            @Override public List<ComplianceRule> rules() { return List.of(); }
        };
        CaptureRequest req = request(List.of(line("L1", "PERF-50ML", 1, "120.00")), List.of(shipTo("1 Market St", "San Francisco", "CA", "94105")), List.of(card("200")));
        ValidationContext ctx = ctx(req, catalog());
        List<ValidationIssue> issues = new ComplianceStep(fake).validate(req, ctx);
        assertThat(issues).extracting(ValidationIssue::code).containsExactly("HAZMAT_AIR", "AGE_VERIFICATION_REQUIRED", "HAZMAT_SIGNATURE");
        assertThat(issues).extracting(ValidationIssue::severity).containsExactly("block", "warn", "warn");
        assertThat(ctx.holds()).singleElement().satisfies(h -> { assertThat(h.type()).isEqualTo("compliance"); assertThat(h.reasonCode()).isEqualTo("AGE_VERIFICATION_REQUIRED"); assertThat(h.blocks()).contains("release"); });
        assertThat(ctx.violations()).hasSize(3);
    }

    // ---- agent_provenance ---------------------------------------------------------------------------------------
    @Test
    void agentProvenanceRequiresEvidenceAndHoldsBeyondMandate() {
        AgentProvenanceStep step = new AgentProvenanceStep();
        CaptureRequest noProv = simple();
        assertThat(step.validate(noProv, ctx(noProv, catalog()))).isEmpty();
        CaptureRequest agentChannel = new CaptureRequest("agent", "A-1", null, "c", null, List.of(line("L1", "TEE-BLK-M", 1, "28.00")), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, "USD", Map.of(), null);
        assertThat(step.validate(agentChannel, ctx(agentChannel, catalog()))).extracting(ValidationIssue::code).containsExactly("provenance_required");

        CaptureRequest incomplete = withProvenance(new CaptureRequest.ProvenanceInput("shopbot", null, null, null, null, null, null, null));
        List<ValidationIssue> issues = step.validate(incomplete, ctx(incomplete, catalog()));
        assertThat(issues.stream().filter(ValidationIssue::blocks)).hasSize(2);
        assertThat(issues).extracting(ValidationIssue::field).contains("provenance.protocol", "provenance.instructionText");

        CaptureRequest beyond = withProvenance(new CaptureRequest.ProvenanceInput("shopbot", "ucp", "m-1", Money.usd("30.00"), "buy two black tees", "sig", "buyer-1", Map.of()));
        ValidationContext ctx = ctx(beyond, catalog());
        List<ValidationIssue> warn = step.validate(beyond, ctx);
        assertThat(warn).extracting(ValidationIssue::code).containsExactly("beyond_mandate");
        assertThat(warn.get(0).blocks()).isFalse();
        assertThat(ctx.holds()).singleElement().satisfies(h -> { assertThat(h.type()).isEqualTo("agent_verification"); assertThat(h.reasonCode()).isEqualTo("BEYOND_MANDATE"); });

        CaptureRequest within = withProvenance(new CaptureRequest.ProvenanceInput("shopbot", "mcp", "m-1", Money.usd("500.00"), "buy two black tees", "sig", "buyer-1", Map.of()));
        ValidationContext okCtx = ctx(within, catalog());
        assertThat(step.validate(within, okCtx)).isEmpty();
        assertThat(okCtx.holds()).isEmpty();
    }

    private static CaptureRequest withProvenance(CaptureRequest.ProvenanceInput p) {
        return new CaptureRequest("agent", "A-2", null, "c", null, List.of(line("L1", "TEE-BLK-M", 2, "28.00")), List.of(shipTo("1 Market St", "San Francisco", "CA", "94105")), List.of(),
            List.of(new CaptureRequest.TenderInput("agent_token", "tok", Money.usd("56.00"), null)), List.of(), List.of(), "ground", null, "USD", Map.of(), p);
    }
}

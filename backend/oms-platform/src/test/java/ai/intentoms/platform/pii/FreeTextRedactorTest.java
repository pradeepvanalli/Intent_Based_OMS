package ai.intentoms.platform.pii;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FreeTextRedactorTest {

    @Test
    void theAuditNoteLosesItsEmailAndPhoneButKeepsItsMeaning() {
        assertThat(FreeTextRedactor.redact("call me at 973-555-0177 or mail quillon.a3a@example.com"))
            .isEqualTo("call me at [phone] or mail [email]");
        assertThat(FreeTextRedactor.redact("Gift for mum, please ship together. Leave with the neighbour."))
            .isEqualTo("Gift for mum, please ship together. Leave with the neighbour.");
    }

    @Test
    void phoneShapes() {
        assertThat(FreeTextRedactor.redact("(973) 555-0177")).isEqualTo("[phone]");
        assertThat(FreeTextRedactor.redact("ring +1 973 555 0177 first")).isEqualTo("ring [phone] first");
        assertThat(FreeTextRedactor.redact("9735550177.")).isEqualTo("[phone].");
        assertThat(FreeTextRedactor.redact("+44 20 7946 0958")).isEqualTo("[phone]");
        assertThat(FreeTextRedactor.redact("tel:973.555.0177")).isEqualTo("tel:[phone]");
    }

    @Test
    void cardShapes() {
        assertThat(FreeTextRedactor.redact("card 4111 1111 1111 1111 exp 12/29")).isEqualTo("card [card] exp 12/29");
        assertThat(FreeTextRedactor.redact("4111-1111-1111-1111")).isEqualTo("[card]");
        assertThat(FreeTextRedactor.redact("378282246310005")).isEqualTo("[card]");
    }

    @Test
    void emailShapes() {
        assertThat(FreeTextRedactor.redact("Pat.Lee+gifts@Example.co.uk, thanks")).isEqualTo("[email], thanks");
        assertThat(FreeTextRedactor.redact("@home after 6")).isEqualTo("@home after 6");
    }

    @Test
    void ordinaryNumbersStay() {
        String text = "2 hoodies, size M, zip 60606, deliver after 2026-10-19 at 10:30, budget $1,299.99, door code 4421, SKU HOOD-GRY-M";
        assertThat(FreeTextRedactor.redact(text)).isEqualTo(text);
        assertThat(FreeTextRedactor.containsPii(text)).isFalse();
        assertThat(FreeTextRedactor.containsPii("mail a@b.io")).isTrue();
        assertThat(FreeTextRedactor.redact(null)).isNull();
        assertThat(FreeTextRedactor.redact("")).isEmpty();
    }

    @Test
    void structuresAreRedactedDeep() {
        Object out = FreeTextRedactor.redactDeep(Map.of("order", Map.of("notes", List.of("text 973-555-0177", "ok"), "lines", 2)));
        assertThat(out).isEqualTo(Map.of("order", Map.of("notes", List.of("text [phone]", "ok"), "lines", 2)));
        assertThat(FreeTextRedactor.redactAll(List.of("a@b.io"))).containsExactly("[email]");
    }

    @org.junit.jupiter.api.Test
    void knownNamesAndAddressesAndStreetPatternsAreRedacted() {
        KnownPii known = new KnownPii(java.util.List.of("Jane Doe", "Jo"), java.util.List.of("400 Oak Ave", "Apt 7"), java.util.List.of("jane@example.com"), java.util.List.of());
        org.assertj.core.api.Assertions.assertThat(FreeTextRedactor.redact("Call jane  DOE at 12 Elm Street, not 400 oak ave", known))
            .isEqualTo("Call [name] at [address], not [address]");
        org.assertj.core.api.Assertions.assertThat(FreeTextRedactor.redact("side door of 4418 Wren Hollow Rd Unit 9 please")).isEqualTo("side door of [address] please");
        org.assertj.core.api.Assertions.assertThat(FreeTextRedactor.redact("ship 2 boxes together, gift for mum, leave at the side door", known))
            .as("no street suffix, no known value: unchanged").isEqualTo("ship 2 boxes together, gift for mum, leave at the side door");
        org.assertj.core.api.Assertions.assertThat(FreeTextRedactor.redact("Jonas will collect", known)).as("values under 3 characters are ignored, words are matched whole")
            .isEqualTo("Jonas will collect");
        org.assertj.core.api.Assertions.assertThat(FreeTextRedactor.redact("Janet Doering", known)).isEqualTo("Janet Doering");
    }

    /**
     * A3 residue: a third party's name — a gift recipient, someone to ask for, whoever a note names that is not the
     * order's own customer or ship-to name — redacted by the cue heuristic alone (no {@link KnownPii} needed). One
     * cue is enough; several in the same note each redact.
     */
    @Test
    void thirdPartyNamesAfterACueAreRedacted() {
        assertThat(FreeTextRedactor.redact("Gift for Marisol Reyes, call Dan at the desk"))
            .isEqualTo("Gift for [name], call [name] at the desk");
        assertThat(FreeTextRedactor.redact("attn: John Smith")).isEqualTo("attn: [name]");
        assertThat(FreeTextRedactor.redact("c/o Maria Lopez")).isEqualTo("c/o [name]");
        assertThat(FreeTextRedactor.redact("ask for Priya Nair at checkout")).isEqualTo("ask for [name] at checkout");
        assertThat(FreeTextRedactor.redact("contact Robert Chen about the pickup")).isEqualTo("contact [name] about the pickup");
        assertThat(FreeTextRedactor.redact("name: Olivia Park")).isEqualTo("name: [name]");
        assertThat(FreeTextRedactor.redact("deliver to Mark Albright please")).isEqualTo("deliver to [name] please");
        assertThat(FreeTextRedactor.redact("call Fatima when you arrive")).isEqualTo("call [name] when you arrive");
        assertThat(FreeTextRedactor.redact("Gift for Grandma Wendy this year")).isEqualTo("Gift for [name] this year");
        assertThat(FreeTextRedactor.redact("deliver to Jean-Paul Martin please")).isEqualTo("deliver to [name] please");
        assertThat(FreeTextRedactor.redact("ask for Naomi at pickup")).isEqualTo("ask for [name] at pickup");
        assertThat(FreeTextRedactor.redact("contact Chen Wu for access")).isEqualTo("contact [name] for access");
    }

    /**
     * A3 residue: the cue heuristic must not flag a product, a city or a carrier — the allowlist (the caller's
     * catalog words, node names and known place names) covers the ones that could pass for a name; a carrier name
     * never matches at all (it is never a plain title-case word); a cue needing a pair leaves a single place name
     * alone by construction; a cue with no capitalised word right after it never matches in the first place.
     */
    @Test
    void productsCitiesAndCarriersAreNeverMistakenForAName() {
        KnownPii catalogAndPlaces = new KnownPii(List.of(), List.of(), List.of(), List.of(), List.of("Running Shoes", "New York", "Blue", "Hoodie"));
        assertThat(FreeTextRedactor.redact("Gift for Running Shoes before the trip", catalogAndPlaces))
            .isEqualTo("Gift for Running Shoes before the trip");
        assertThat(FreeTextRedactor.redact("ship to New York please", catalogAndPlaces)).isEqualTo("ship to New York please");
        assertThat(FreeTextRedactor.redact("call Blue Hoodie support", catalogAndPlaces)).isEqualTo("call Blue Hoodie support");
        assertThat(FreeTextRedactor.redact("call FedEx for pickup")).isEqualTo("call FedEx for pickup");
        assertThat(FreeTextRedactor.redact("contact UPS about the delay")).isEqualTo("contact UPS about the delay");
        assertThat(FreeTextRedactor.redact("ask for DHL driver")).isEqualTo("ask for DHL driver");
        assertThat(FreeTextRedactor.redact("to USPS drop box")).isEqualTo("to USPS drop box");
        assertThat(FreeTextRedactor.redact("call Wednesday to confirm")).isEqualTo("call Wednesday to confirm");
        assertThat(FreeTextRedactor.redact("ask for December delivery")).isEqualTo("ask for December delivery");
        assertThat(FreeTextRedactor.redact("name of the item is Blue Hoodie")).isEqualTo("name of the item is Blue Hoodie");
        assertThat(FreeTextRedactor.redact("for Chicago shipment")).as("a pair-only cue leaves a single place name alone").isEqualTo("for Chicago shipment");
        assertThat(FreeTextRedactor.redact("Gift for mum, please ship together")).isEqualTo("Gift for mum, please ship together");
    }
}

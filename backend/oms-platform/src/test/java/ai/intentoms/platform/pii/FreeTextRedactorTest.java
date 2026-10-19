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
}

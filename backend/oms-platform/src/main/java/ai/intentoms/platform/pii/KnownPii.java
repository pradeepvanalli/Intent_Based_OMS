package ai.intentoms.platform.pii;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * NF-07: the personal data the caller already knows about the subject of a piece of free text (an order's customer
 * name, ship-to name and address lines, email and phone), so {@link FreeTextRedactor} can remove those exact values
 * wherever a person typed them into a note, a return reason or a message. Values shorter than three characters are
 * ignored (they would match ordinary words).
 *
 * <p>{@code allowlist} (A3 residue): words and phrases {@link FreeTextRedactor}'s conservative third-party-name cue
 * heuristic must never treat as a name even when they follow a cue like "for" or "call" — a product, node or place
 * name the caller already knows is not a person. Unlike the other fields it is not personal data and is not itself
 * redacted anywhere.
 */
public record KnownPii(List<String> names, List<String> addresses, List<String> emails, List<String> phones, List<String> allowlist) {
    public static final KnownPii NONE = new KnownPii(List.of(), List.of(), List.of(), List.of(), List.of());

    public KnownPii(List<String> names, List<String> addresses, List<String> emails, List<String> phones) { this(names, addresses, emails, phones, List.of()); }

    public KnownPii {
        names = clean(names);
        addresses = clean(addresses);
        emails = clean(emails);
        phones = clean(phones);
        allowlist = clean(allowlist);
    }

    public boolean isEmpty() { return names.isEmpty() && addresses.isEmpty() && emails.isEmpty() && phones.isEmpty(); }

    /** This context plus another's values (for text that mentions two subjects). */
    public KnownPii and(KnownPii other) {
        if (other == null || other.isEmpty()) return this;
        return new KnownPii(concat(names, other.names), concat(addresses, other.addresses), concat(emails, other.emails), concat(phones, other.phones), concat(allowlist, other.allowlist));
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static List<String> clean(Collection<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).map(String::strip).filter(v -> v.length() >= 3).distinct().toList();
    }
}

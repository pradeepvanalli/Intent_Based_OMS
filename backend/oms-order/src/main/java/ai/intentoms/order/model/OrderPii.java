package ai.intentoms.order.model;

import ai.intentoms.platform.pii.KnownPii;

import java.util.ArrayList;
import java.util.List;

/**
 * NF-07: what an order already knows about the person behind it (customer name, email and phone; every address's
 * name, address lines and phone), handed to {@link ai.intentoms.platform.pii.FreeTextRedactor} so those values are
 * removed from any free text about the order before it reaches a decision input or a model prompt.
 *
 * <p>A3 residue: the same order also knows words that must never be mistaken for a third party's name by the
 * redactor's cue heuristic — the SKUs' own catalog words (each line's description) and the order's own known place
 * names (every address's city and state), added to {@link KnownPii#allowlist()}. This is the order's own catalog and
 * places, not the tenant's whole catalog or node directory (a plain {@code Order} carries neither); a caller that
 * has the tenant's catalog or node registry to hand may extend the allowlist further before calling the redactor.
 */
public final class OrderPii {
    private OrderPii() {}

    public static KnownPii known(Order order) {
        if (order == null) return KnownPii.NONE;
        List<String> names = new ArrayList<>(), addresses = new ArrayList<>(), emails = new ArrayList<>(), phones = new ArrayList<>(), allowlist = new ArrayList<>();
        Customer c = order.customer();
        if (c != null) {
            names.add(c.name());
            emails.add(c.email());
            phones.add(c.phone());
        }
        if (order.addresses() != null) for (Address a : order.addresses()) {
            if (a == null) continue;
            names.add(a.name());
            addresses.add(a.line1());
            addresses.add(a.line2());
            phones.add(a.phone());
            allowlist.add(a.city());
            allowlist.add(a.state());
        }
        if (order.lines() != null) for (var l : order.lines()) {
            if (l == null || l.description() == null) continue;
            allowlist.add(l.description());
            for (String word : l.description().split("\\s+")) allowlist.add(word);
        }
        return new KnownPii(names, addresses, emails, phones, allowlist);
    }
}

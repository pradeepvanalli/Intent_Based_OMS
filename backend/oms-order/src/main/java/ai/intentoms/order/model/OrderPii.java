package ai.intentoms.order.model;

import ai.intentoms.platform.pii.KnownPii;

import java.util.ArrayList;
import java.util.List;

/**
 * NF-07: what an order already knows about the person behind it (customer name, email and phone; every address's
 * name, address lines and phone), handed to {@link ai.intentoms.platform.pii.FreeTextRedactor} so those values are
 * removed from any free text about the order before it reaches a decision input or a model prompt.
 */
public final class OrderPii {
    private OrderPii() {}

    public static KnownPii known(Order order) {
        if (order == null) return KnownPii.NONE;
        List<String> names = new ArrayList<>(), addresses = new ArrayList<>(), emails = new ArrayList<>(), phones = new ArrayList<>();
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
        }
        return new KnownPii(names, addresses, emails, phones);
    }
}

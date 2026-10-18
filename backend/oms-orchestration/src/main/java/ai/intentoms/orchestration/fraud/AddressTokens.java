package ai.intentoms.orchestration.fraud;

import ai.intentoms.order.model.Address;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * A stable, non-reversible token for a postal address, so fraud features and ring detection (FD-01, FD-04) can group
 * orders by address without putting the address itself into a decision's input. Same normalisation in Java and SQL
 * ({@link #SQL}): md5 of lower(line1) | lower(line2) | lower(zip without spaces) | lower(country).
 */
public final class AddressTokens {
    private AddressTokens() {}

    /** SQL expression over an {@code order_address.address} jsonb column aliased {@code a}. */
    public static final String SQL = "md5(lower(coalesce(a.address->>'line1','')) || '|' || lower(coalesce(a.address->>'line2','')) || '|' || "
        + "lower(replace(coalesce(a.address->>'zip',''), ' ', '')) || '|' || lower(coalesce(a.address->>'country','')))";

    public static String token(Address a) {
        if (a == null || a.line1() == null || a.line1().isBlank()) return null;
        String s = lower(a.line1()) + "|" + lower(a.line2()) + "|" + lower(a.zip()).replace(" ", "") + "|" + lower(a.country());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}

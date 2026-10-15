package ai.intentoms.order.validation;

import ai.intentoms.order.capture.CaptureRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic, network-free {@link AddressVerifier} for tests and demos (FR-CAP-05 address verification):
 * US addresses must carry a known two-letter state and a 5 or 9 digit ZIP whose three-digit prefix is plausible for
 * the state (USPS sectional centre ranges); a {@code line1} containing "UNDELIVERABLE" is reported undeliverable; PO
 * boxes are detected from {@code line1} and typed {@code po_box}. Non-US addresses are passed through unverified.
 * Registered by {@code OrderAutoConfiguration} unless another {@link AddressVerifier} bean exists.
 */
public class MockAddressVerifier implements AddressVerifier {
    private static final Pattern ZIP = Pattern.compile("^\\d{5}(-\\d{4})?$");
    private static final Pattern PO_BOX = Pattern.compile("(?i)\\b(p\\.?\\s*o\\.?\\s*box|post\\s+office\\s+box)\\b");
    /** State → inclusive three-digit ZIP prefix ranges (USPS). */
    static final Map<String, int[][]> ZIP_RANGES = Map.ofEntries(
        Map.entry("AL", r(350, 369)), Map.entry("AK", r(995, 999)), Map.entry("AZ", r(850, 865)), Map.entry("AR", r(716, 729)),
        Map.entry("CA", r(900, 961)), Map.entry("CO", r(800, 816)), Map.entry("CT", r(60, 69)), Map.entry("DE", r(197, 199)),
        Map.entry("DC", r(200, 205)), Map.entry("FL", r(320, 349)), Map.entry("GA", r(300, 319, 398, 399)), Map.entry("HI", r(967, 968)),
        Map.entry("ID", r(832, 838)), Map.entry("IL", r(600, 629)), Map.entry("IN", r(460, 479)), Map.entry("IA", r(500, 528)),
        Map.entry("KS", r(660, 679)), Map.entry("KY", r(400, 427)), Map.entry("LA", r(700, 714)), Map.entry("ME", r(39, 49)),
        Map.entry("MD", r(206, 219)), Map.entry("MA", r(10, 27, 55, 55)), Map.entry("MI", r(480, 499)), Map.entry("MN", r(550, 567)),
        Map.entry("MS", r(386, 397)), Map.entry("MO", r(630, 658)), Map.entry("MT", r(590, 599)), Map.entry("NE", r(680, 693)),
        Map.entry("NV", r(889, 898)), Map.entry("NH", r(30, 38)), Map.entry("NJ", r(70, 89)), Map.entry("NM", r(870, 884)),
        Map.entry("NY", r(100, 149, 4, 5, 63, 63)), Map.entry("NC", r(270, 289)), Map.entry("ND", r(580, 588)), Map.entry("OH", r(430, 459)),
        Map.entry("OK", r(730, 749)), Map.entry("OR", r(970, 979)), Map.entry("PA", r(150, 196)), Map.entry("RI", r(28, 29)),
        Map.entry("SC", r(290, 299)), Map.entry("SD", r(570, 577)), Map.entry("TN", r(370, 385)), Map.entry("TX", r(750, 799, 733, 733, 885, 885)),
        Map.entry("UT", r(840, 847)), Map.entry("VT", r(50, 59)), Map.entry("VA", r(220, 246, 201, 201)), Map.entry("WA", r(980, 994)),
        Map.entry("WV", r(247, 268)), Map.entry("WI", r(530, 549)), Map.entry("WY", r(820, 831)), Map.entry("PR", r(6, 9)));

    private static int[][] r(int... bounds) {
        int[][] out = new int[bounds.length / 2][];
        for (int i = 0; i < out.length; i++) out[i] = new int[]{bounds[2 * i], bounds[2 * i + 1]};
        return out;
    }

    @Override
    public AddressVerification verify(CaptureRequest.AddressInput a) {
        List<String> messages = new ArrayList<>();
        String line1 = a.line1() == null ? "" : a.line1().trim();
        boolean poBox = PO_BOX.matcher(line1).find();
        String addressType = poBox ? "po_box" : a.addressType();
        if (line1.toUpperCase(Locale.ROOT).contains("UNDELIVERABLE")) {
            return new AddressVerification("undeliverable", addressType, null, null, List.of("Carrier reports the address as undeliverable"));
        }
        String country = a.country() == null || a.country().isBlank() ? "US" : a.country().trim().toUpperCase(Locale.ROOT);
        if (!"US".equals(country) && !"USA".equals(country)) {
            return new AddressVerification("unverified", addressType, null, null, List.of("Verification not available for " + country));
        }
        if (line1.isEmpty()) messages.add("line1 is required");
        if (a.city() == null || a.city().isBlank()) messages.add("city is required");
        String state = a.state() == null ? "" : a.state().trim().toUpperCase(Locale.ROOT);
        int[][] ranges = ZIP_RANGES.get(state);
        if (ranges == null) messages.add("state '" + a.state() + "' is not a US state code");
        String zip = a.zip() == null ? "" : a.zip().trim();
        if (!ZIP.matcher(zip).matches()) messages.add("zip '" + a.zip() + "' must be 5 digits or ZIP+4");
        else if (ranges != null && !plausible(Integer.parseInt(zip.substring(0, 3)), ranges)) messages.add("zip " + zip + " does not belong to " + state);
        if (!messages.isEmpty()) return new AddressVerification("invalid", addressType, null, null, messages);
        return new AddressVerification("verified", addressType == null ? "residential" : addressType, null, null, poBox ? List.of("PO box address") : List.of());
    }

    static boolean plausible(int prefix, int[][] ranges) {
        for (int[] range : ranges) if (prefix >= range[0] && prefix <= range[1]) return true;
        return false;
    }
}

package ai.intentoms.platform.pii;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NF-07: the one redactor for free text a person typed (order notes, gift messages, delivery instructions, return
 * reasons, contact messages) before it is copied into a decision's input, state or context features, or into a model
 * prompt. Email addresses become {@value #EMAIL}, card-like numbers (13 to 19 digits, spaces or dashes allowed) become
 * {@value #CARD} and phone-like numbers (7 to 12 digits, with the usual separators and an optional leading +) become
 * {@value #PHONE}; everything else is kept, so the text still says what the customer wants ("gift, ship together, call
 * [phone] before delivery"). ISO dates are not phone numbers. Deterministic and stateless; null stays null.
 *
 * <p>Names and street addresses: with a {@link KnownPii} context the subject's own known values (customer and ship-to
 * names, address lines, email, phone) are removed wherever they appear, by exact case-insensitive match on word
 * boundaries ({@value #NAME}, {@value #ADDRESS}, ...). A conservative street-address pattern also catches addresses
 * the caller does not know: a house number, one to three capitalised street words and a street suffix ("12 Elm
 * Street", "4418 Wren Hollow Rd", optionally with "Apt 4" / "Unit 9").
 *
 * <p>A3 residue (audit 3/4): a name nobody told the redactor about — a gift recipient, a neighbour, anyone who is not
 * the order's own customer or ship-to name — still used to reach the decision input verbatim, because free-text name
 * detection would just as happily remove product and place words. Conservatively: a capitalised name pair right
 * after a cue ("for", "to", "attn", "c/o", "contact", "name") and a single capitalised name right after "call" or
 * "ask for" ({@value #NAME}), unless the word is on {@link KnownPii#allowlist()} (the caller's catalog words, node
 * names and known place names) or is a day/month name. Anything else — a name with no cue, a cue with no capitalised
 * word after it — is left alone, same as before.
 */
public final class FreeTextRedactor {
    public static final String EMAIL = "[email]";
    public static final String PHONE = "[phone]";
    public static final String CARD = "[card]";
    public static final String NAME = "[name]";
    public static final String ADDRESS = "[address]";

    /** House number, 1-3 capitalised street words, a street suffix, an optional unit. */
    private static final Pattern STREET_RE = Pattern.compile(
        "\\b\\d{1,6}[A-Za-z]?(?:\\s+[A-Z][A-Za-z'.-]*){1,3}\\s+(?i:street|st|avenue|ave|road|rd|boulevard|blvd|lane|ln|drive|dr|court|ct|way|place|pl|terrace|ter|"
        + "circle|cir|parkway|pkwy|highway|hwy|square|sq|trail|trl|crescent|cres|close|row|alley|aly)\\b\\.?(?:,?\\s+(?i:apt|apartment|unit|suite|ste|flat|#)\\s*[A-Za-z0-9-]+)?");

    private static final Pattern EMAIL_RE = Pattern.compile("(?i)(?<![\\w.+-])[a-z0-9._%+-]+@[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}(?![\\w-])");
    /** A run of digits with the separators people put in phone and card numbers; classified by its digit count. */
    private static final Pattern NUMBER_RE = Pattern.compile("(?<![\\w@])\\+?\\(?\\d(?:[\\d\\s().-]*\\d)?(?![\\w@])");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /**
     * A3 residue: cue + one capitalised "word" (title case only — plain {@code [A-Z][a-z]+}, so an all-caps carrier
     * like UPS/USPS/DHL or a mixed-case one like FedEx never matches) up to three words long. Which cues accept a
     * single word ({@code call}, {@code ask for}) vs. require a pair (everything else) is decided after matching, in
     * {@link #redactThirdPartyNameCues}, since Java regex cannot vary a quantifier by which alternative matched.
     */
    private static final Pattern NAME_CUE_RE = Pattern.compile(
        "(?i)\\b(for|to|attn|c/o|call|ask\\s+for|contact|name)\\b:?\\s+"
        + "((?-i:[A-Z][a-z]+\\b(?:['-][A-Z][a-z]+\\b)?)(?:\\s+(?-i:[A-Z][a-z]+\\b(?:['-][A-Z][a-z]+\\b)?)){0,2})");
    private static final Set<String> SINGLE_WORD_CUES = Set.of("call", "ask for");
    /** Capitalised words that are never a name regardless of the cue (a day or month reads fine after "call"). */
    private static final Set<String> NEVER_A_NAME = Set.of(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december");

    private FreeTextRedactor() {}

    public static String redact(String text) { return redact(text, KnownPii.NONE); }

    /**
     * As {@link #redact(String)}, first removing the subject's own known personal data (exact, case-insensitive, on word
     * boundaries; longest value first so a full address goes before a part of it) and street addresses.
     */
    public static String redact(String text, KnownPii known) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        if (known != null && !known.isEmpty()) {
            List<String[]> values = new ArrayList<>();
            known.emails().forEach(v -> values.add(new String[]{v, EMAIL}));
            known.addresses().forEach(v -> values.add(new String[]{v, ADDRESS}));
            known.names().forEach(v -> values.add(new String[]{v, NAME}));
            known.phones().forEach(v -> values.add(new String[]{v, PHONE}));
            values.sort((a, b) -> Integer.compare(b[0].length(), a[0].length()));
            for (String[] v : values) out = exact(v[0]).matcher(out).replaceAll(Matcher.quoteReplacement(v[1]));
        }
        out = redactThirdPartyNameCues(out, known == null ? List.of() : known.allowlist());
        out = STREET_RE.matcher(out).replaceAll(Matcher.quoteReplacement(ADDRESS));
        out = EMAIL_RE.matcher(out).replaceAll(Matcher.quoteReplacement(EMAIL));
        Matcher m = NUMBER_RE.matcher(out);
        StringBuilder sb = new StringBuilder(out.length());
        while (m.find()) {
            String run = m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(classify(run)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * A3 residue: {@value #NAME} for a capitalised name right after a cue — a pair for most cues, a single word
     * allowed only for "call" / "ask for" ({@link #SINGLE_WORD_CUES}) — unless the candidate is on the allowlist or
     * is a day/month name, in which case the text is left exactly as it was.
     */
    private static String redactThirdPartyNameCues(String text, List<String> allowlist) {
        Matcher m = NAME_CUE_RE.matcher(text);
        if (!m.find()) return text;
        StringBuilder sb = new StringBuilder(text.length());
        int last = 0;
        do {
            String cue = m.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            String candidate = m.group(2);
            String[] words = candidate.split("\\s+");
            boolean pairRequired = !SINGLE_WORD_CUES.contains(cue);
            boolean skip = (pairRequired && words.length < 2) || isAllowlisted(candidate, words, allowlist);
            sb.append(text, last, m.start(2));
            sb.append(skip ? candidate : NAME);
            last = m.end(2);
        } while (m.find());
        sb.append(text, last, text.length());
        return sb.toString();
    }

    private static boolean isAllowlisted(String candidate, String[] words, List<String> allowlist) {
        for (String w : words) if (NEVER_A_NAME.contains(w.toLowerCase(Locale.ROOT))) return true;
        for (String a : allowlist) {
            if (a.equalsIgnoreCase(candidate)) return true;
            for (String w : words) if (a.equalsIgnoreCase(w)) return true;
        }
        return false;
    }

    /** True when {@link #redact} would change the text. */
    public static boolean containsPii(String text) { return text != null && !text.equals(redact(text)); }

    /** A known value as a case-insensitive pattern on word boundaries, any run of whitespace matching any other. */
    private static Pattern exact(String value) {
        String[] parts = value.strip().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) { if (i > 0) sb.append("\\s+"); sb.append(Pattern.quote(parts[i])); }
        return Pattern.compile("(?<![\\w])" + sb + "(?![\\w])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Every string inside maps and lists redacted (keys kept); other values unchanged. */
    public static Object redactDeep(Object value) { return redactDeep(value, KnownPii.NONE); }

    public static Object redactDeep(Object value, KnownPii known) {
        if (value instanceof String s) return redact(s, known);
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(k, redactDeep(v, known)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object v : list) out.add(redactDeep(v, known));
            return out;
        }
        return value;
    }

    public static List<String> redactAll(List<String> texts) {
        if (texts == null) return null;
        List<String> out = new ArrayList<>(texts.size());
        for (String t : texts) out.add(redact(t));
        return out;
    }

    private static String classify(String run) {
        String trimmed = run.strip();
        int digits = 0;
        for (int i = 0; i < trimmed.length(); i++) if (Character.isDigit(trimmed.charAt(i))) digits++;
        if (digits >= 13 && digits <= 19 && !trimmed.startsWith("+") && !trimmed.contains("(")) return keepEdges(run, CARD);
        if (digits >= 7 && digits <= 15 && !ISO_DATE.matcher(trimmed).matches()) return keepEdges(run, PHONE);
        return run;
    }

    /** The match may include leading or trailing whitespace from the separator class; keep it around the token. */
    private static String keepEdges(String run, String token) {
        int start = 0, end = run.length();
        while (start < end && Character.isWhitespace(run.charAt(start))) start++;
        while (end > start && Character.isWhitespace(run.charAt(end - 1))) end--;
        return run.substring(0, start) + token + run.substring(end);
    }
}

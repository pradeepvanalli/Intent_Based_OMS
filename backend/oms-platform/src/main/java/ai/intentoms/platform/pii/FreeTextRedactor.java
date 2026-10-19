package ai.intentoms.platform.pii;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NF-07: the one redactor for free text a person typed (order notes, gift messages, delivery instructions, return
 * reasons, contact messages) before it is copied into a decision's input, state or context features, or into a model
 * prompt. Email addresses become {@value #EMAIL}, card-like numbers (13 to 19 digits, spaces or dashes allowed) become
 * {@value #CARD} and phone-like numbers (7 to 12 digits, with the usual separators and an optional leading +) become
 * {@value #PHONE}; everything else is kept, so the text still says what the customer wants ("gift, ship together, call
 * [phone] before delivery"). ISO dates are not phone numbers. Deterministic and stateless; null stays null.
 */
public final class FreeTextRedactor {
    public static final String EMAIL = "[email]";
    public static final String PHONE = "[phone]";
    public static final String CARD = "[card]";

    private static final Pattern EMAIL_RE = Pattern.compile("(?i)(?<![\\w.+-])[a-z0-9._%+-]+@[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}(?![\\w-])");
    /** A run of digits with the separators people put in phone and card numbers; classified by its digit count. */
    private static final Pattern NUMBER_RE = Pattern.compile("(?<![\\w@])\\+?\\(?\\d(?:[\\d\\s().-]*\\d)?(?![\\w@])");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private FreeTextRedactor() {}

    public static String redact(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = EMAIL_RE.matcher(text).replaceAll(Matcher.quoteReplacement(EMAIL));
        Matcher m = NUMBER_RE.matcher(out);
        StringBuilder sb = new StringBuilder(out.length());
        while (m.find()) {
            String run = m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(classify(run)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** True when {@link #redact} would change the text. */
    public static boolean containsPii(String text) { return text != null && !text.equals(redact(text)); }

    /** Every string inside maps and lists redacted (keys kept); other values unchanged. */
    @SuppressWarnings("unchecked")
    public static Object redactDeep(Object value) {
        if (value instanceof String s) return redact(s);
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(k, redactDeep(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object v : list) out.add(redactDeep(v));
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

package ai.intentoms.copilot.llm;

import ai.intentoms.platform.json.Json;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-checks that generated text states only facts present in its source (FR-XAI-03, FR-CS-05, GR-15): every number
 * (and, for customer text, every date and amount) in the output must appear in the serialised source. Numbers are
 * compared after normalisation (thousands separators removed, trailing zeros stripped) so "1,250.50" matches 1250.5.
 */
public final class Grounding {
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z_\\-])\\d[\\d,]*(?:\\.\\d+)?");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern MONEY = Pattern.compile("[$€£]\\s?\\d[\\d,]*(?:\\.\\d{1,2})?|\\d[\\d,]*(?:\\.\\d{1,2})?\\s?(?:USD|EUR|GBP)");

    private Grounding() {}

    /** Numbers in {@code text} that do not occur in {@code source} (source may be any object; it is serialised to JSON). */
    public static List<String> ungroundedNumbers(String text, Object source) {
        Set<String> allowed = numbers(source instanceof String s ? s : Json.write(source));
        List<String> missing = new java.util.ArrayList<>();
        for (String n : numbers(text)) if (!allowed.contains(n)) missing.add(n);
        return missing;
    }

    /** Dates (ISO) and money amounts in {@code text} missing from {@code source}. */
    public static List<String> ungroundedDatesAndAmounts(String text, Object source) {
        String src = source instanceof String s ? s : Json.write(source);
        Set<String> allowedNumbers = numbers(src);
        List<String> missing = new java.util.ArrayList<>();
        Matcher d = ISO_DATE.matcher(text);
        while (d.find()) if (!src.contains(d.group())) missing.add(d.group());
        Matcher m = MONEY.matcher(text);
        while (m.find()) {
            Set<String> nums = numbers(m.group());
            for (String n : nums) if (!allowedNumbers.contains(n)) missing.add(m.group());
        }
        return missing;
    }

    static Set<String> numbers(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null) return out;
        Matcher m = NUMBER.matcher(s);
        while (m.find()) {
            String raw = m.group().replace(",", "");
            try { out.add(new BigDecimal(raw).stripTrailingZeros().toPlainString()); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    public static String normalise(String number) {
        try { return new BigDecimal(number.replace(",", "")).stripTrailingZeros().toPlainString(); } catch (NumberFormatException e) { return number; }
    }
}

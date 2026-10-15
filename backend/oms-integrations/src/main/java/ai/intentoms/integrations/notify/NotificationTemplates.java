package ai.intentoms.integrations.notify;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Customer message templates ({@code classpath:notify/templates/<key>.txt}; section 11 Notifications, FR-MON-04
 * {@code contact_customer}, FR-CS-01). A template is {@code subject: …} on the first line, a {@code ---} separator and the
 * body; both carry {@code {{slot}}} placeholders.
 *
 * <p><b>GR-15</b>: a slot is filled only from the context map of order facts the caller passes (plus {@code order_id}
 * and {@code brand}); a slot with no fact renders empty and is reported in {@link Rendered#missingSlots()}. Nothing a
 * model produced can reach the customer through here because the map is built by code, never from decision output,
 * and templates themselves are static files.
 */
@Component
public class NotificationTemplates {
    private static final Pattern SLOT = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");
    public static final String GENERIC = "generic";
    private final Map<String, Template> templates;

    public record Template(String key, String subject, String body) {
        public Set<String> slots() {
            Set<String> s = new java.util.LinkedHashSet<>();
            for (String part : List.of(subject, body)) { Matcher m = SLOT.matcher(part); while (m.find()) s.add(m.group(1)); }
            return s;
        }
    }

    public record Rendered(String template, String subject, String body, List<String> missingSlots) {}

    public NotificationTemplates() { this.templates = load(); }

    public boolean has(String key) { return key != null && templates.containsKey(key); }

    public Set<String> keys() { return Collections.unmodifiableSet(templates.keySet()); }

    public Template get(String key) {
        Template t = templates.get(key);
        if (t == null) throw new IllegalArgumentException("Unknown notification template '" + key + "'");
        return t;
    }

    /** Renders {@code key} (falls back to {@code generic} for unknown keys) from scalar facts in {@code context}. */
    public Rendered render(String key, Map<String, Object> context) {
        Template t = templates.getOrDefault(key, templates.get(GENERIC));
        List<String> missing = new ArrayList<>();
        String subject = fill(t.subject(), context, missing);
        String body = fill(t.body(), context, missing);
        return new Rendered(t.key(), subject.strip(), body.strip(), List.copyOf(new java.util.LinkedHashSet<>(missing)));
    }

    static String fill(String text, Map<String, Object> ctx, List<String> missing) {
        Matcher m = SLOT.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String slot = m.group(1);
            Object v = ctx == null ? null : ctx.get(slot);
            String s = scalar(v);
            if (s == null) { missing.add(slot); s = ""; }
            m.appendReplacement(sb, Matcher.quoteReplacement(s));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Only scalars become text; maps / lists are not rendered (they are not customer-facing facts). */
    static String scalar(Object v) {
        if (v == null) return null;
        if (v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof java.time.temporal.Temporal) return String.valueOf(v);
        return null;
    }

    private static Map<String, Template> load() {
        Map<String, Template> out = new TreeMap<>();
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:notify/templates/*.txt");
            for (Resource r : files) {
                String name = r.getFilename();
                if (name == null) continue;
                String key = name.substring(0, name.length() - 4);
                try (InputStream in = r.getInputStream()) { out.put(key, parse(key, new String(in.readAllBytes(), StandardCharsets.UTF_8))); }
            }
        } catch (IOException e) { throw new IllegalStateException("Cannot load notification templates", e); }
        if (!out.containsKey(GENERIC)) throw new IllegalStateException("notify/templates/generic.txt is required");
        return new LinkedHashMap<>(out);
    }

    static Template parse(String key, String raw) {
        String text = raw.replace("\r\n", "\n");
        int sep = text.indexOf("\n---\n");
        String head = sep < 0 ? "" : text.substring(0, sep);
        String body = sep < 0 ? text : text.substring(sep + 5);
        String subject = head.strip();
        if (subject.regionMatches(true, 0, "subject:", 0, 8)) subject = subject.substring(8).strip();
        return new Template(key, subject, body.strip());
    }
}

package ai.intentoms.simulation.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Accepts an ISO instant ("2026-10-05T00:00:00Z"), a plain date ("2026-10-05", taken at 00:00 UTC) or epoch millis
 * for simulation date ranges, so the UI's date pickers and API clients can both drive what-if jobs.
 */
public class LenientInstant extends JsonDeserializer<Instant> {
    @Override
    public Instant deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        if (p.currentToken().isNumeric()) return Instant.ofEpochMilli(p.getLongValue());
        String v = p.getValueAsString();
        if (v == null || v.isBlank()) return null;
        v = v.trim();
        try { return Instant.parse(v); } catch (DateTimeParseException ignored) { }
        try { return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v).atStartOfDay(ZoneOffset.UTC).toInstant(); }
        catch (DateTimeParseException e) { throw ctx.weirdStringException(v, Instant.class, "expected an ISO-8601 instant or date"); }
    }
}

package ai.intentoms.sourcing.api;

import ai.intentoms.learning.learner.PromiseBufferLearner;
import ai.intentoms.learning.learner.PromiseConversionRepository;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.platform.time.TimeSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * CX-02 ingest: the storefront reports each promise it showed (days promised, the buffer included) and whether the
 * shopper bought, so the promise-buffer learner can weigh conversion against on-time delivery. Authenticated callers
 * only (a storefront or agent key with {@code promise.read}), so anonymous traffic cannot skew learning.
 */
@RestController
@RequestMapping("/api/v1/promise")
public class PromiseConversionController {
    private final PromiseConversionRepository conversions;
    private final TimeSource time;

    public PromiseConversionController(PromiseConversionRepository conversions, TimeSource time) {
        this.conversions = conversions;
        this.time = time;
    }

    /** @param segment overrides the segment, else {@code channel:<channel>}, else {@code default} */
    public record Conversion(String impressionId, String channel, String segment, String orderId, Integer promisedDays, Integer bufferDays, Boolean converted) {}

    @PostMapping("/conversion")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission({Permissions.PROMISE_READ, Permissions.ORDER_READ})
    public Map<String, Object> conversion(@RequestBody Conversion body) {
        return Map.of("stored", store(body));
    }

    @PostMapping("/conversions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission({Permissions.PROMISE_READ, Permissions.ORDER_READ})
    public Map<String, Object> conversions(@RequestBody List<Conversion> body) {
        if (body == null || body.isEmpty()) throw new ValidationException("at least one conversion is required");
        if (body.size() > 1000) throw new ValidationException("at most 1000 conversions per request");
        int stored = 0;
        for (Conversion c : body) if (store(c)) stored++;
        return Map.of("received", body.size(), "stored", stored);
    }

    private boolean store(Conversion c) {
        if (c == null || c.promisedDays() == null || c.converted() == null) throw new ValidationException("promisedDays and converted are required");
        if (c.promisedDays() < 0 || c.promisedDays() > 365) throw new ValidationException("promisedDays must be between 0 and 365");
        int buffer = c.bufferDays() == null ? 0 : c.bufferDays();
        if (buffer < 0 || buffer > 30) throw new ValidationException("bufferDays must be between 0 and 30");
        String segment = c.segment() != null && !c.segment().isBlank() ? c.segment() : PromiseBufferLearner.segment(c.channel());
        String id = c.impressionId() == null || c.impressionId().isBlank() ? Ids.prefixed("imp") : c.impressionId();
        return conversions.record(id, segment, c.orderId(), c.promisedDays(), buffer, c.converted(), time.now());
    }
}

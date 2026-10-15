package ai.intentoms.sourcing.rates;

import ai.intentoms.platform.audit.AuditLog;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Seeds the tenant's rate card from {@code shipping-rates.yaml} (idempotent upsert). */
@Service
public class RateCardLoader {
    private static final Logger log = LoggerFactory.getLogger(RateCardLoader.class);
    private final RateCardRepository repo;
    private final AuditLog audit;

    public RateCardLoader(RateCardRepository repo, AuditLog audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public RateCard loadFile(Path file) {
        try { return loadYaml(Files.readString(file), file.toString()); }
        catch (IOException e) { throw new ValidationException("Cannot read shipping rates file " + file + ": " + e.getMessage()); }
    }

    @Transactional
    public RateCard loadYaml(String yaml, String source) {
        Map<String, Object> doc = Json.yamlMap(yaml);
        RateCard card = repo.save(doc);
        audit.record("shipping_rates.loaded", "shipping_rate_card", source, "Loaded rate card", Map.of("services", card.services()));
        log.info("Loaded shipping rate card with services {} from {}", card.services(), source);
        return card;
    }
}

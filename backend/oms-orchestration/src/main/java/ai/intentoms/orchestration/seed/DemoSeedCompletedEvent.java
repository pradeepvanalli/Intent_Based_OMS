package ai.intentoms.orchestration.seed;

import java.util.List;

/**
 * Published once {@link DemoSeeder} has seeded every configured tenant at startup. Seeders that build on the demo
 * data (reference data for the self-optimizing layer, for example) listen for this instead of guessing an
 * {@code @Order} relative to {@code ApplicationReadyEvent}.
 */
public record DemoSeedCompletedEvent(List<String> tenantIds) {}

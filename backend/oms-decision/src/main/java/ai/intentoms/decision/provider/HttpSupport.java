package ai.intentoms.decision.provider;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Shared budget enforcement for HTTP providers (GR-07): run a call on a virtual thread and give up at the deadline. */
final class HttpSupport {
    private static final ExecutorService VIRTUAL = Executors.newVirtualThreadPerTaskExecutor();

    private HttpSupport() {}

    record HttpResult(int status, String body) {}

    static <T> T withBudget(String provider, int budgetMs, Supplier<T> call) {
        CompletableFuture<T> f = CompletableFuture.supplyAsync(call, VIRTUAL).orTimeout(Math.max(1, budgetMs), java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
            return f.join();
        } catch (CompletionException e) {
            Throwable c = e.getCause();
            if (c instanceof TimeoutException) throw ProviderException.timeout(provider, budgetMs);
            if (c instanceof ProviderException pe) throw pe;
            throw ProviderException.of(provider, c == null ? e.getMessage() : c.getMessage(), c);
        }
    }

    /** 50-150 ms jitter before a retry on 429/529. */
    static long retryJitterMs() { return 50 + ThreadLocalRandom.current().nextLong(101); }

    static Duration factoryTimeout(int ms) { return Duration.ofMillis(Math.max(100, ms)); }
}

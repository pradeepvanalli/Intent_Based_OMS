package ai.intentoms.app.glue.soo;

import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /api/v1/learning/seed-status}: whether this tenant's self-optimizing demo data is in place. {@code complete}
 * is false while seeding runs, when it is switched off, and on tenants it never seeds; {@code ids} names every seeded
 * proposal, experiment, objective version and digest so the UI can badge them as demo data. {@code scripts/e2e.sh}
 * waits on {@code complete}.
 */
@RestController
public class SooSeedStatusController {
    private final SooDemoSeeder seeder;

    public SooSeedStatusController(SooDemoSeeder seeder) { this.seeder = seeder; }

    @GetMapping("/api/v1/learning/seed-status")
    @RequiresPermission(Permissions.LEARNING_READ)
    public Map<String, Object> status() {
        String tenant = TenantContext.require();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", seeder.enabled() && tenant.equals(seeder.tenant()));
        out.put("running", seeder.running(tenant));
        var marker = seeder.marker();
        out.put("complete", marker.map(m -> Boolean.TRUE.equals(m.get("complete"))).orElse(false) && !seeder.running(tenant));
        marker.ifPresent(m -> {
            out.put("version", m.get("version"));
            out.put("seededAt", m.get("seededAt"));
            out.put("durationMs", m.get("durationMs"));
            out.put("counts", m.get("counts"));
            out.put("ids", m.get("ids"));
            out.put("pausedLearners", m.get("pausedLearners"));
            if (m.get("warnings") != null) out.put("warnings", m.get("warnings"));
        });
        out.put("actor", SooDemoSeeder.SEED);
        return out;
    }
}

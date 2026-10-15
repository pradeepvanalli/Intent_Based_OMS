package ai.intentoms.orchestration.seed;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code GET /api/v1/public/tenants}: the seeded demo tenants for the login page's tenant picker. Unauthenticated
 * ({@code /api/v1/public/**} is a default public path) and only registered when {@code oms.demo.seed=true}, so a
 * production deployment never lists its tenants. Entries: {@code {tenantId, name, vertical, description}} from each
 * tenant folder's {@code tenant-config.yaml}.
 */
@RestController
@RequestMapping("/api/v1/public")
@ConditionalOnProperty(name = "oms.demo.seed", havingValue = "true")
public class PublicTenantsController {
    private final DemoSeeder seeder;

    public PublicTenantsController(DemoSeeder seeder) { this.seeder = seeder; }

    @GetMapping("/tenants")
    public List<TenantMetadata> tenants() { return seeder.tenants(); }
}

package ai.intentoms.copilot.api;

import ai.intentoms.copilot.digest.Digest;
import ai.intentoms.copilot.digest.DigestService;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Margin Guardian digest endpoints. */
@RestController
@RequestMapping("/api/v1/copilot/digest")
public class DigestController {
    private final DigestService digest;

    public DigestController(DigestService digest) { this.digest = digest; }

    public record RunBody(Integer days) {}

    @GetMapping
    @RequiresPermission(Permissions.ANALYTICS_READ)
    public Digest generate(@RequestParam(defaultValue = "7") int days) { return digest.generate(days); }

    @PostMapping("/run")
    @RequiresPermission(Permissions.ANALYTICS_READ)
    public Digest run(@RequestParam(defaultValue = "7") int days) { return digest.run(days); }

    @GetMapping("/latest")
    @RequiresPermission(Permissions.ANALYTICS_READ)
    public Digest latest() { return digest.latest().orElseThrow(() -> new NotFoundException("No digest has been run yet")); }

    @GetMapping("/history")
    @RequiresPermission(Permissions.ANALYTICS_READ)
    public List<Digest> history(@RequestParam(defaultValue = "10") int limit) { return digest.history(limit); }
}

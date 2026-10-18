package ai.intentoms.learning.api;

import ai.intentoms.learning.experiment.ExperimentManager;
import ai.intentoms.learning.model.Experiment;
import ai.intentoms.learning.model.ExperimentMode;
import ai.intentoms.learning.model.ExperimentStatus;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** EX-02..EX-06 REST surface. Separate controller from {@code LearningController} per the file-ownership split
 * this build used — same {@code /api/v1/learning} base path, different sub-paths. */
@RestController
@RequestMapping("/api/v1/learning/experiments")
public class ExperimentController {
    private final ExperimentManager experiments;

    public ExperimentController(ExperimentManager experiments) { this.experiments = experiments; }

    public record StartRequest(String proposalId, ExperimentMode mode, double trafficShare, Map<String, Object> stopRule) {}
    public record StopRequest(ExperimentStatus status, Map<String, Object> result) {}

    @GetMapping
    @RequiresPermission(Permissions.LEARNING_READ)
    public List<Experiment> running() { return experiments.running(); }

    @PostMapping
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Experiment start(@RequestBody StartRequest body) {
        if (body == null || body.proposalId() == null || body.mode() == null) throw new ValidationException("proposalId and mode are required");
        return experiments.start(body.proposalId(), body.mode(), body.trafficShare(), body.stopRule());
    }

    @PostMapping("/{id}/stop")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public void stop(@PathVariable String id, @RequestBody(required = false) StopRequest body) {
        experiments.stop(id, body == null || body.status() == null ? ExperimentStatus.STOPPED : body.status(), body == null ? null : body.result());
    }

    @PostMapping("/holdout")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Experiment ensureHoldout(@RequestParam(value = "share", defaultValue = "0.05") double share) {
        return experiments.ensureHoldout(share);
    }
}

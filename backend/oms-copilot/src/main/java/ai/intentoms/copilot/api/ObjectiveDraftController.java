package ai.intentoms.copilot.api;

import ai.intentoms.copilot.objective.ObjectiveDraftService;
import ai.intentoms.learning.model.ObjectiveProfile;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * XP-07: {@code POST /api/v1/learning/objective/draft {"text": "..."}} turns plain-English priorities into a DRAFT
 * objective profile. Approval is {@code POST /api/v1/learning/objective/{version}/approve} by a different person.
 */
@RestController
@RequestMapping("/api/v1/learning/objective")
public class ObjectiveDraftController {
    private final ObjectiveDraftService drafts;
    private final AuthorizationService authz;

    public ObjectiveDraftController(ObjectiveDraftService drafts, AuthorizationService authz) {
        this.drafts = drafts;
        this.authz = authz;
    }

    public record DraftRequest(String text) {}

    @PostMapping("/draft")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public ObjectiveProfile draft(@RequestBody DraftRequest body) {
        if (body == null) throw new ValidationException("text is required");
        return drafts.draft(body.text(), authz.requirePrincipal().actorRef());
    }
}

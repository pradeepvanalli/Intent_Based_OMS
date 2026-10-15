package ai.intentoms.statemachine;

import ai.intentoms.statemachine.model.MigrationReport;
import ai.intentoms.statemachine.model.StateModel;
import ai.intentoms.statemachine.model.ValidationReport;

import java.util.List;

/**
 * Versioned, tenant-scoped store of published state models (FR-SM-06, FR-SM-09, FR-SM-10). Publishing runs the static
 * checks and refuses models with errors; every published version is immutable and cached.
 */
public interface StateModelRegistry {
    /** Parse + static checks without storing anything. Parse failures are reported as a single {@code parse_error} issue. */
    ValidationReport validate(String yaml);

    /**
     * Store a new version. Throws {@code ValidationException} when static checks report errors or {@code reason} is blank,
     * {@code ConflictException} when the (modelId, version) already exists. Records an audit entry and a
     * {@code statemodel.published} event on the {@code config} aggregate.
     */
    StateModel publish(String yaml, String reason);

    StateModel get(String modelId, int version);

    /** Highest published version. Throws {@code NotFoundException} when the model is unknown. */
    StateModel latest(String modelId);

    boolean exists(String modelId, int version);

    List<StateModel> versions(String modelId);

    List<String> modelIds();

    /** Move entities pinned to {@code fromVersion} onto {@code toVersion} (FR-SM-06); {@code dryRun} only reports. */
    MigrationReport migrate(String modelId, int fromVersion, int toVersion, boolean dryRun);
}

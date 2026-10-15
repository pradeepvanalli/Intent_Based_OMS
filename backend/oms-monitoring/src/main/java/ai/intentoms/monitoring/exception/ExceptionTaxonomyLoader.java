package ai.intentoms.monitoring.exception;

import ai.intentoms.platform.json.Json;
import ai.intentoms.workflow.task.TaskQueue;
import ai.intentoms.workflow.task.TaskQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Seeds the exception taxonomy from {@code config/tenants/<t>/exception-taxonomy.yaml} (FR-MON-03): {@code exceptionTypes}
 * into {@code exception_type} and {@code queues} into the workflow module's {@code task_queue} via
 * {@link TaskQueueService#upsert}. Every queue referenced by a type is ensured to exist. Idempotent.
 */
@Component
public class ExceptionTaxonomyLoader {
    private static final Logger log = LoggerFactory.getLogger(ExceptionTaxonomyLoader.class);
    private final ExceptionTypeRepository types;
    private final TaskQueueService queues;

    public ExceptionTaxonomyLoader(ExceptionTypeRepository types, TaskQueueService queues) {
        this.types = types;
        this.queues = queues;
    }

    public List<ExceptionTypeDef> loadFile(Path file) {
        try { return load(Json.yamlMap(Files.readString(file))); }
        catch (IOException e) { throw new IllegalStateException("Cannot read taxonomy file " + file, e); }
    }

    @SuppressWarnings("unchecked")
    public List<ExceptionTypeDef> load(Map<String, Object> yaml) {
        Set<String> seenQueues = new LinkedHashSet<>();
        if (yaml.get("queues") instanceof List<?> ql) {
            for (Object o : ql) {
                Map<String, Object> q = (Map<String, Object>) o;
                Set<String> skills = new LinkedHashSet<>();
                if (q.get("skills") instanceof List<?> sl) sl.forEach(s -> skills.add(String.valueOf(s)));
                String name = String.valueOf(q.get("name"));
                queues.upsert(new TaskQueue(name, intOf(q.get("slaMinutes"), TaskQueueService.DEFAULT_SLA_MINUTES), str(q.get("escalateTo")), skills, str(q.get("description"))));
                seenQueues.add(name);
            }
        }
        List<ExceptionTypeDef> out = new ArrayList<>();
        if (yaml.get("exceptionTypes") instanceof List<?> tl) {
            for (Object o : tl) {
                Map<String, Object> m = (Map<String, Object>) o;
                ExceptionTypeDef d = new ExceptionTypeDef(str(m.get("type")), str(m.get("severity")), str(m.get("queue")), str(m.get("playbook")), intOf(m.get("slaMinutes"), 240), str(m.get("description")));
                if (!seenQueues.contains(d.queue())) { queues.ensure(d.queue()); seenQueues.add(d.queue()); }
                out.add(types.upsert(d));
            }
        }
        log.info("Loaded {} exception types and {} queues", out.size(), seenQueues.size());
        return out;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static int intOf(Object o, int dflt) { return o instanceof Number n ? n.intValue() : o == null ? dflt : Integer.parseInt(o.toString()); }
}

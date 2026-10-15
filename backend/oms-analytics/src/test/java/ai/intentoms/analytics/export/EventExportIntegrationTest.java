package ai.intentoms.analytics.export;

import ai.intentoms.analytics.AnalyticsTestBase;
import ai.intentoms.analytics.api.AnalyticsController;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.json.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** NDJSON CloudEvents export (FR-ANL-03): one well-formed CloudEvent per line, tenant-scoped, filterable by type prefix. */
class EventExportIntegrationTest extends AnalyticsTestBase {
    @Autowired EventExportService export;
    @Autowired AnalyticsController controller;

    @Test
    void streamsCloudEventsAsNdjson() throws Exception {
        Order o = newOrder("delivered", List.of(group("ex", 0, "DC-EAST", "2.00", LocalDate.now())), null);
        delivered(o.id());
        Instant from = time.now().minus(Duration.ofMinutes(5)), to = time.now().plus(Duration.ofMinutes(1));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long n = export.write(from, to, List.of("order."), out);
        String[] lines = out.toString(StandardCharsets.UTF_8).strip().split("\n");
        assertThat(n).isGreaterThanOrEqualTo(1);
        assertThat(lines).hasSize((int) n);
        boolean sawDelivered = false;
        for (String line : lines) {
            Map<String, Object> ce = Json.readMap(line);
            assertThat(ce).containsEntry("specversion", "1.0").containsEntry("tenantid", TENANT).containsKeys("id", "source", "subject", "time", "sequence", "position", "actor", "data");
            assertThat((String) ce.get("type")).startsWith("ai.intentoms.order.");
            if (o.id().equals(ce.get("subject")) && "ai.intentoms.order.transitioned".equals(ce.get("type"))) {
                sawDelivered = true;
                @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) ce.get("data");
                assertThat(data).containsEntry("to", "delivered");
            }
        }
        assertThat(sawDelivered).isTrue();

        ResponseEntity<StreamingResponseBody> response = controller.exportEvents(from.toString(), to.toString(), "order.transitioned");
        assertThat(response.getHeaders().getContentType()).isEqualTo(AnalyticsController.NDJSON);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        response.getBody().writeTo(body);
        assertThat(body.toString(StandardCharsets.UTF_8)).contains("\"ai.intentoms.order.transitioned\"").doesNotContain("order.captured");
        assertThat(export.write(from, to, List.of("nothing.matches."), new ByteArrayOutputStream())).isZero();
    }
}

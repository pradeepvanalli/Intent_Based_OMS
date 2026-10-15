package ai.intentoms.platform.ledger;

import ai.intentoms.platform.json.Json;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Optional Kafka sink (enable with oms.kafka.enabled=true and spring.kafka.bootstrap-servers). Key = aggregate id for per-order ordering. */
@Component
@ConditionalOnProperty(prefix = "oms.kafka", name = "enabled", havingValue = "true")
public class KafkaEventPublisher implements EventPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final String topicPrefix;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka, org.springframework.core.env.Environment env) {
        this.kafka = kafka;
        this.topicPrefix = env.getProperty("oms.kafka.topic-prefix", "oms.events");
    }

    @Override
    public void publish(Event e) {
        String topic = topicPrefix + "." + e.aggregateType();
        kafka.send(topic, e.aggregateId(), Json.write(e.toCloudEvent()));
    }
}

package com.ecom.common.events;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/** Loads the canonical {@code kafka-topics.yaml} from the classpath (same file Terraform reads). */
public final class KafkaTopicDefinitions {

    public record TopicDefinition(String name, int partitions, Map<String, String> config) {}

    public record Definitions(int defaultPartitions, List<TopicDefinition> topics) {}

    @SuppressWarnings("unchecked")
    public static Definitions load() {
        try (InputStream in = KafkaTopicDefinitions.class.getResourceAsStream("/kafka-topics.yaml")) {
            if (in == null) {
                throw new IllegalStateException("kafka-topics.yaml not on classpath");
            }
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> defaults = (Map<String, Object>) root.get("defaults");
            int defaultPartitions = (Integer) defaults.get("partitions");
            List<Map<String, Object>> raw = (List<Map<String, Object>>) root.get("topics");
            List<TopicDefinition> topics = raw.stream()
                    .map(t -> new TopicDefinition(
                            (String) t.get("name"),
                            (Integer) t.getOrDefault("partitions", defaultPartitions),
                            Map.copyOf((Map<String, String>) t.getOrDefault("config", Map.of()))))
                    .toList();
            return new Definitions(defaultPartitions, topics);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private KafkaTopicDefinitions() {}
}

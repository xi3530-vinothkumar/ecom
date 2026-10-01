package com.ecom.search;

import com.ecom.testing.ContainerConfigs;
import org.springframework.context.annotation.Import;
import static org.awaitility.Awaitility.await;

import com.ecom.common.events.EventEnvelope;
import com.ecom.common.events.EventTopics;
import com.ecom.common.events.Money;
import com.ecom.common.events.payload.ProductChangedPayload;
import com.ecom.common.events.payload.ProductDeletedPayload;
import com.ecom.common.events.payload.StockLevelChangedPayload;
import com.ecom.search.infrastructure.ElasticsearchProductIndex;
import com.ecom.search.infrastructure.EventListeners;
import com.ecom.testing.Containers;
import com.ecom.testing.KafkaTestSupport;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** Shared context for all search ITs: real Elasticsearch + Kafka, events published as a foreign producer. */
@Import({ContainerConfigs.Elasticsearch.class, ContainerConfigs.Kafka.class})
@SpringBootTest
@AutoConfigureMockMvc
public abstract class SearchIntegrationTest {

    private static Admin admin;

    @Autowired protected ObjectMapper mapper;
    @Autowired protected ElasticsearchProductIndex index;
    @Autowired protected MockMvc mvc;

    @BeforeAll
    static void admin() {
        admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap()));
    }

    @AfterAll
    static void closeAdmin() {
        admin.close();
    }

    protected static String bootstrap() {
        return Containers.kafka().getBootstrapServers();
    }

    protected static String id() {
        return UUID.randomUUID().toString();
    }

    protected static String sku() {
        return "SKU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    protected static ProductChangedPayload product(String productId, String name, long version, String categoryId,
            String brand, String... skus) {
        var variants = Arrays.stream(skus)
                .map(s -> new ProductChangedPayload.Variant(s, "Size M", Money.of("19.99", "EUR")))
                .toList();
        return new ProductChangedPayload(productId, name, "Description of " + name, categoryId, brand, true, version,
                variants);
    }

    protected EventEnvelope send(String topic, String key, Object payload) {
        return KafkaTestSupport.sendPayload(bootstrap(), mapper, topic, key, payload);
    }

    protected void resend(String topic, String key, EventEnvelope env) {
        KafkaTestSupport.send(bootstrap(), mapper, topic, key, env);
    }

    protected void productChanged(ProductChangedPayload p, boolean created) {
        send(created ? EventTopics.PRODUCT_CREATED : EventTopics.PRODUCT_UPDATED, p.productId(), p);
    }

    protected void productDeleted(String productId, long version, String... skus) {
        send(EventTopics.PRODUCT_DELETED, productId, new ProductDeletedPayload(productId, List.of(skus), version));
    }

    protected void stock(String sku, int qty, long version) {
        send(EventTopics.STOCK_LEVEL_CHANGED, sku, new StockLevelChangedPayload(sku, qty, qty > 0, version));
    }

    /** Waits until both indexer groups have committed past everything published so far. */
    protected void awaitIndexed() {
        awaitDrained(EventListeners.CATALOG_GROUP,
                List.of(EventTopics.PRODUCT_CREATED, EventTopics.PRODUCT_UPDATED, EventTopics.PRODUCT_DELETED));
        awaitDrained(EventListeners.INVENTORY_GROUP, List.of(EventTopics.STOCK_LEVEL_CHANGED));
        index.refresh();
    }

    private void awaitDrained(String group, List<String> topics) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
            var partitions = admin.describeTopics(topics).allTopicNames().get().values().stream()
                    .flatMap(d -> d.partitions().stream().map(p -> new TopicPartition(d.name(), p.partition())))
                    .toList();
            Map<TopicPartition, Long> ends = admin.listOffsets(
                            partitions.stream().collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest())))
                    .all().get().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().offset()));
            Map<TopicPartition, OffsetAndMetadata> committed =
                    admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();
            return ends.entrySet().stream().allMatch(e -> e.getValue() == 0
                    || (committed.get(e.getKey()) != null && committed.get(e.getKey()).offset() >= e.getValue()));
        });
    }
}

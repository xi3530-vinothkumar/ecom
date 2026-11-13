package com.ecom.cart.it;

import com.ecom.testing.ContainerConfigs;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/** One shared context (Redis + Kafka + fake Catalog) for all cart ITs; tests isolate via unique ids/SKUs. */
@Import({ContainerConfigs.Redis.class, ContainerConfigs.Kafka.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.grpc.server.port=0", "management.tracing.export.enabled=false"})
abstract class AbstractCartIT {

    static final FakeCatalog CATALOG = new FakeCatalog();

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry r) {
        r.add("spring.grpc.client.channel.catalog.target", () -> "localhost:" + CATALOG.port());
    }

    @Autowired protected StringRedisTemplate redis;
    @Autowired protected ObjectMapper mapper;

    protected static String sku(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected static String userId() {
        return "user-" + UUID.randomUUID();
    }
}

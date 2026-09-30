package com.ecom.testing;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * JVM-wide singleton containers (started lazily, stopped by Ryuk at JVM exit), so every test class in
 * a module shares one Postgres / Kafka / Redis / Elasticsearch. Usage:
 *
 * <pre>
 * &#64;DynamicPropertySource
 * static void props(DynamicPropertyRegistry r) {
 *     Containers.registerPostgres(r);
 *     Containers.registerKafka(r);
 * }
 * </pre>
 */
public final class Containers {

    public static final String POSTGRES_IMAGE = "postgres:16-alpine";
    public static final String KAFKA_IMAGE = "apache/kafka-native:3.8.0";
    public static final String REDIS_IMAGE = "redis:7-alpine";
    public static final String ELASTICSEARCH_IMAGE = "docker.elastic.co/elasticsearch/elasticsearch:9.5.4";

    private static PostgreSQLContainer<?> postgres;
    private static KafkaContainer kafka;
    private static GenericContainer<?> redis;
    private static GenericContainer<?> elasticsearch;

    public static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                    .withDatabaseName("test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand("postgres", "-c", "max_connections=300")
                    // log-based readiness + host-side port check (Rancher/Colima port-forwarding lags startup)
                    .waitingFor(new WaitAllStrategy()
                            .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
                            .withStrategy(Wait.forListeningPort()));
            postgres.start();
        }
        return postgres;
    }

    public static synchronized KafkaContainer kafka() {
        if (kafka == null) {
            kafka = new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE));
            kafka.waitingFor(new WaitAllStrategy()
                    .withStrategy(Wait.forLogMessage(".*Transitioning from RECOVERY to RUNNING.*", 1))
                    .withStrategy(Wait.forListeningPort()));
            kafka.start();
        }
        return kafka;
    }

    public static synchronized GenericContainer<?> redis() {
        if (redis == null) {
            redis = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379).waitingFor(Wait.forListeningPort());
            redis.start();
        }
        return redis;
    }

    public static synchronized GenericContainer<?> elasticsearch() {
        if (elasticsearch == null) {
            elasticsearch = new GenericContainer<>(DockerImageName.parse(ELASTICSEARCH_IMAGE))
                    .withExposedPorts(9200)
                    .withEnv("discovery.type", "single-node")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                    .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).forStatusCode(200));
            elasticsearch.start();
        }
        return elasticsearch;
    }

    public static void registerPostgres(DynamicPropertyRegistry r) {
        var pg = postgres();
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    /** Registers Kafka and enables local topic creation (RF=1) from kafka-topics.yaml. */
    public static void registerKafka(DynamicPropertyRegistry r) {
        var k = kafka();
        r.add("spring.kafka.bootstrap-servers", k::getBootstrapServers);
        r.add("ecom.kafka.create-topics", () -> "true");
    }

    public static void registerRedis(DynamicPropertyRegistry r) {
        var redis = redis();
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    public static void registerElasticsearch(DynamicPropertyRegistry r) {
        var es = elasticsearch();
        r.add("spring.elasticsearch.uris", () -> "http://" + es.getHost() + ":" + es.getMappedPort(9200));
    }

    private Containers() {}
}

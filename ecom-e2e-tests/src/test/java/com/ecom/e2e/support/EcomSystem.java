package com.ecom.e2e.support;

import com.ecom.testing.Containers;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The whole saga under test, started once per JVM: Testcontainers Postgres (one database per service),
 * Kafka and Redis, plus every service fat jar as a child process wired to them and to each other.
 */
public final class EcomSystem {

    private static final Logger log = LoggerFactory.getLogger(EcomSystem.class);

    /** Reservation TTL used by the e2e system; short so the timeout/expiry scenarios run fast. */
    public static final Duration RESERVATION_TTL = Duration.ofSeconds(20);
    public static final Duration SWEEP_INTERVAL = Duration.ofSeconds(2);

    /** service name -> database name (null = no Postgres). Order matters only for log readability. */
    private static final Map<String, String> SERVICES = new LinkedHashMap<>();

    static {
        SERVICES.put("user", "userdb");
        SERVICES.put("catalog", "catalogdb");
        SERVICES.put("inventory", "inventorydb");
        SERVICES.put("cart", null);
        SERVICES.put("order", "orderdb");
        SERVICES.put("payment", "paymentdb");
        SERVICES.put("notification", "notificationdb");
    }

    private static EcomSystem instance;
    private static RuntimeException startupFailure;

    private final Map<String, ServiceProcess> processes = new LinkedHashMap<>();
    private final Path repoRoot;
    private final Path logDir;
    private PostgreSQLContainer<?> postgres;
    private String kafkaBootstrap;
    private EventLog eventLog;
    private volatile boolean stopped;

    private EcomSystem(Path repoRoot, Path logDir) {
        this.repoRoot = repoRoot;
        this.logDir = logDir;
    }

    public static synchronized EcomSystem get() {
        if (startupFailure != null) {
            throw new IllegalStateException("E2E system failed to start earlier in this JVM", startupFailure);
        }
        if (instance == null) {
            Path root = Path.of(System.getProperty("e2e.repoRoot", Path.of("").toAbsolutePath().getParent().toString()))
                    .toAbsolutePath().normalize();
            Path logs = Path.of(System.getProperty("e2e.logDir", root.resolve("ecom-e2e-tests/target/e2e-logs").toString()));
            EcomSystem sys = new EcomSystem(root, logs);
            Runtime.getRuntime().addShutdownHook(new Thread(sys::stop, "e2e-shutdown"));
            try {
                sys.start();
            } catch (RuntimeException e) {
                sys.stop();
                startupFailure = e;
                throw e;
            }
            instance = sys;
        }
        return instance;
    }

    private void start() {
        long t0 = System.nanoTime();
        Map<String, Path> jars = locateJars();
        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // infrastructure in parallel
        var pg = CompletableFuture.supplyAsync(Containers::postgres);
        var kafka = CompletableFuture.supplyAsync(Containers::kafka);
        var redis = CompletableFuture.supplyAsync(Containers::redis);
        postgres = pg.join();
        kafkaBootstrap = kafka.join().getBootstrapServers();
        var redisContainer = redis.join();
        createDatabases();
        log.info("Infrastructure up in {} ms", (System.nanoTime() - t0) / 1_000_000);

        Map<String, int[]> ports = new HashMap<>();
        Set<Integer> used = new HashSet<>();
        for (String svc : SERVICES.keySet()) {
            ports.put(svc, new int[] {freePort(used), freePort(used)});
        }

        Map<String, String> common = new HashMap<>();
        common.put("KAFKA_BOOTSTRAP_SERVERS", kafkaBootstrap);
        common.put("KAFKA_CREATE_TOPICS", "true");
        common.put("REDIS_HOST", redisContainer.getHost());
        common.put("REDIS_PORT", String.valueOf(redisContainer.getMappedPort(6379)));
        common.put("DB_USERNAME", postgres.getUsername());
        common.put("DB_PASSWORD", postgres.getPassword());
        common.put("TRACING_ENABLED", "false");
        common.put("SPRING_PROFILES_ACTIVE", "plain-logs");
        for (String peer : List.of("user", "catalog", "inventory", "cart", "order", "payment")) {
            common.put(peer.toUpperCase() + "_GRPC_ADDRESS", "localhost:" + ports.get(peer)[1]);
        }

        for (var e : SERVICES.entrySet()) {
            String svc = e.getKey();
            Map<String, String> env = new HashMap<>(common);
            env.put("HTTP_PORT", String.valueOf(ports.get(svc)[0]));
            env.put("GRPC_PORT", String.valueOf(ports.get(svc)[1]));
            if (e.getValue() != null) {
                env.put("DB_URL", jdbcUrl(e.getValue()));
            }
            switch (svc) {
                case "user" -> env.put("JWT_EPHEMERAL_KEY", "true");
                case "inventory" -> {
                    env.put("RESERVATION_TTL", RESERVATION_TTL.toSeconds() + "s");
                    env.put("RESERVATION_SWEEP_INTERVAL", SWEEP_INTERVAL.toSeconds() + "s");
                }
                case "payment" -> env.put("PSP_MODE", "fake");
                case "notification" -> env.put("NOTIFICATION_CHANNEL", "log");
                default -> { }
            }
            processes.put(svc, new ServiceProcess(svc, jars.get(svc), logDir.resolve(svc + ".log"),
                    ports.get(svc)[0], ports.get(svc)[1], env));
        }

        for (ServiceProcess p : processes.values()) {
            try {
                p.start();
            } catch (IOException ex) {
                throw new UncheckedIOException("Cannot launch " + p.name(), ex);
            }
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        List<CompletableFuture<Void>> ready = new ArrayList<>();
        for (ServiceProcess p : processes.values()) {
            ready.add(CompletableFuture.runAsync(() -> p.awaitReady(http, Duration.ofSeconds(180))));
        }
        try {
            CompletableFuture.allOf(ready.toArray(CompletableFuture[]::new)).join();
        } catch (java.util.concurrent.CompletionException ex) {
            throw ex.getCause() instanceof RuntimeException re ? re : ex;
        }
        eventLog = new EventLog(kafkaBootstrap);
        log.info("E2E system ready in {} ms; ports {}; logs in {}", (System.nanoTime() - t0) / 1_000_000,
                describePorts(), logDir);
    }

    private Map<String, Path> locateJars() {
        Map<String, Path> jars = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String svc : SERVICES.keySet()) {
            String module = "ecom-" + svc + "-service";
            Path jar = repoRoot.resolve(module).resolve("target").resolve(module + "-0.1.0-SNAPSHOT.jar");
            if (!Files.isRegularFile(jar)) {
                missing.add(jar.toString());
            }
            jars.put(svc, jar);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Service fat jars missing: " + missing
                    + "\nBuild them first from the repo root:  mvn -DskipTests package"
                    + "  (or: mvn -DskipTests install -pl ecom-user-service,ecom-catalog-service,ecom-inventory-service,"
                    + "ecom-cart-service,ecom-order-service,ecom-payment-service,ecom-notification-service)");
        }
        return jars;
    }

    private void createDatabases() {
        try (var conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var st = conn.createStatement()) {
            for (String db : SERVICES.values()) {
                if (db == null) {
                    continue;
                }
                try (var rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + db + "'")) {
                    if (rs.next()) {
                        continue;
                    }
                }
                st.execute("CREATE DATABASE " + db);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Cannot create service databases", e);
        }
    }

    public String jdbcUrl(String db) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + db;
    }

    public java.sql.Connection connect(String db) throws java.sql.SQLException {
        return DriverManager.getConnection(jdbcUrl(db), postgres.getUsername(), postgres.getPassword());
    }

    private static int freePort(Set<Integer> used) {
        while (true) {
            try (ServerSocket s = new ServerSocket(0)) {
                s.setReuseAddress(true);
                int p = s.getLocalPort();
                if (used.add(p)) {
                    return p;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private String describePorts() {
        StringBuilder sb = new StringBuilder();
        processes.values().forEach(p -> sb.append(p.name()).append('=').append(p.httpPort()).append('/')
                .append(p.grpcPort()).append(' '));
        return sb.toString().trim();
    }

    public int httpPort(String svc) {
        return processes.get(svc).httpPort();
    }

    public String kafkaBootstrap() {
        return kafkaBootstrap;
    }

    public EventLog events() {
        return eventLog;
    }

    public ServiceProcess process(String svc) {
        return processes.get(svc);
    }

    public synchronized void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        if (eventLog != null) {
            eventLog.close();
        }
        List<CompletableFuture<Void>> stops = new ArrayList<>();
        for (ServiceProcess p : processes.values()) {
            stops.add(CompletableFuture.runAsync(p::stop));
        }
        CompletableFuture.allOf(stops.toArray(CompletableFuture[]::new)).join();
        // containers are JVM singletons reaped by Ryuk at JVM exit
    }
}

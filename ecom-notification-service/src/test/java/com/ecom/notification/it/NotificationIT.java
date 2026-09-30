package com.ecom.notification.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecom.common.events.EventEnvelope;
import com.ecom.common.events.EventTopics;
import com.ecom.common.events.Money;
import com.ecom.common.events.payload.OrderCancelledPayload;
import com.ecom.common.events.payload.OrderConfirmedPayload;
import com.ecom.common.events.payload.OrderLinePayload;
import com.ecom.common.events.payload.UserRegisteredPayload;
import com.ecom.notification.NotificationServiceApplication;
import com.ecom.notification.domain.OutgoingNotification;
import com.ecom.notification.infrastructure.NotificationSender;
import com.ecom.testing.Containers;
import com.ecom.testing.KafkaTestSupport;
import com.ecom.user.proto.v1.GetUserRequest;
import com.ecom.user.proto.v1.User;
import com.ecom.user.proto.v1.UserServiceGrpc;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        classes = {NotificationServiceApplication.class, NotificationIT.RecordingSenderConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"ecom.outbox.poll-interval=1s", "management.tracing.export.enabled=false"})
class NotificationIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Map<String, User> USERS = new ConcurrentHashMap<>();
    private static final Server FAKE_USER_SERVICE = startFakeUserService();

    static final List<OutgoingNotification> SENT = new CopyOnWriteArrayList<>();
    static final Set<String> FAIL_ONCE = ConcurrentHashMap.newKeySet();

    @TestConfiguration
    static class RecordingSenderConfig {
        @Bean
        @Primary
        NotificationSender recordingSender() {
            return n -> {
                if (FAIL_ONCE.remove(n.recipient())) {
                    throw new IllegalStateException("SMTP 421 try again later");
                }
                SENT.add(n);
            };
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        Containers.registerPostgres(r);
        Containers.registerKafka(r);
        r.add("ecom.grpc.clients.user.address", () -> "localhost:" + FAKE_USER_SERVICE.getPort());
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private static String newUser() {
        String id = "u-" + UUID.randomUUID();
        USERS.put(id, User.newBuilder().setUserId(id).setEmail(id + "@example.com").setFullName("Ann Example").build());
        return id;
    }

    private EventEnvelope send(String topic, String key, Object payload) {
        return KafkaTestSupport.sendPayload(Containers.kafka().getBootstrapServers(), mapper, topic, key, payload);
    }

    private List<Map<String, Object>> logRows(EventEnvelope env) {
        return jdbc.queryForList("SELECT * FROM notification_log WHERE event_id = ? ORDER BY created_at", env.eventId());
    }

    private void awaitRow(EventEnvelope env, String status) {
        await().atMost(TIMEOUT).until(() -> logRows(env).stream().anyMatch(r -> status.equals(r.get("status"))));
    }

    private List<OutgoingNotification> sentTo(String userId) {
        return SENT.stream().filter(n -> n.recipient().equals(userId + "@example.com")).toList();
    }

    private static OrderConfirmedPayload confirmed(String orderId, String userId) {
        return new OrderConfirmedPayload(orderId, userId,
                List.of(new OrderLinePayload("A", 2), new OrderLinePayload("B", 1)), Money.of("35.81", "EUR"));
    }

    @Test
    void orderConfirmedSendsOnceAndLogsSent() {
        String user = newUser();
        String orderId = UUID.randomUUID().toString();

        EventEnvelope env = send(EventTopics.ORDER_CONFIRMED, orderId, confirmed(orderId, user));
        awaitRow(env, "SENT");

        List<Map<String, Object>> rows = logRows(env);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("user_id", user)
                .containsEntry("template_key", "ORDER_CONFIRMED")
                .containsEntry("channel", "EMAIL")
                .containsEntry("recipient_masked", "u***@example.com");
        List<OutgoingNotification> sent = sentTo(user);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().subject()).isEqualTo("Your order " + orderId + " is confirmed");
        assertThat(sent.getFirst().body()).contains("Hi Ann Example").contains("35.81 EUR").contains("3 item(s)");
    }

    @Test
    void duplicateEventProducesOneLogRowAndOneSend() {
        String user = newUser();
        String orderId = UUID.randomUUID().toString();
        EventEnvelope env = send(EventTopics.ORDER_CONFIRMED, orderId, confirmed(orderId, user));
        awaitRow(env, "SENT");

        KafkaTestSupport.send(Containers.kafka().getBootstrapServers(), mapper, EventTopics.ORDER_CONFIRMED, orderId, env);
        // same key => same partition: once the marker is handled, the duplicate was consumed before it
        EventEnvelope marker = send(EventTopics.ORDER_CONFIRMED, orderId, confirmed(orderId, user));
        awaitRow(marker, "SENT");

        assertThat(logRows(env)).hasSize(1);
        assertThat(sentTo(user)).hasSize(2); // original + marker, never the duplicate
    }

    @Test
    void orderCancelledUsesReasonSpecificTemplate() {
        String user = newUser();
        String orderId = UUID.randomUUID().toString();

        EventEnvelope env = send(EventTopics.ORDER_CANCELLED, orderId, new OrderCancelledPayload(
                orderId, user, OrderCancelledPayload.PAYMENT_TIMEOUT, List.of(new OrderLinePayload("A", 1))));
        awaitRow(env, "SENT");

        assertThat(logRows(env).getFirst()).containsEntry("template_key", "ORDER_CANCELLED_PAYMENT_TIMEOUT");
        assertThat(sentTo(user).getFirst().body()).contains("did not receive payment confirmation");
    }

    @Test
    void userRegisteredSendsWelcome() {
        String user = newUser();

        EventEnvelope env = send(EventTopics.USER_REGISTERED, user, new UserRegisteredPayload(user));
        awaitRow(env, "SENT");

        assertThat(logRows(env).getFirst()).containsEntry("template_key", "WELCOME");
        assertThat(sentTo(user).getFirst().subject()).isEqualTo("Welcome to Ecom");
    }

    @Test
    void unknownUserIsRecordedFailedWithoutSending() {
        String ghost = "u-ghost-" + UUID.randomUUID();

        EventEnvelope env = send(EventTopics.USER_REGISTERED, ghost, new UserRegisteredPayload(ghost));
        awaitRow(env, "FAILED");

        assertThat(logRows(env)).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("error", "user not found").containsEntry("recipient_masked", null));
        assertThat(SENT).noneMatch(n -> n.recipient().startsWith(ghost));
    }

    @Test
    void transientSendFailureIsAuditedAndRetried() {
        String user = newUser();
        FAIL_ONCE.add(user + "@example.com");
        String orderId = UUID.randomUUID().toString();

        EventEnvelope env = send(EventTopics.ORDER_CONFIRMED, orderId, confirmed(orderId, user));
        awaitRow(env, "SENT");

        assertThat(logRows(env)).extracting(r -> r.get("status")).containsExactly("FAILED", "SENT");
        assertThat((String) logRows(env).getFirst().get("error")).contains("SMTP 421");
        assertThat(sentTo(user)).hasSize(1);
    }

    private static Server startFakeUserService() {
        try {
            Server s = NettyServerBuilder.forPort(0).addService(new UserServiceGrpc.UserServiceImplBase() {
                @Override
                public void getUser(GetUserRequest req, StreamObserver<User> out) {
                    User u = USERS.get(req.getUserId());
                    if (u == null) {
                        out.onError(Status.NOT_FOUND.withDescription("no such user").asRuntimeException());
                        return;
                    }
                    out.onNext(u);
                    out.onCompleted();
                }
            }).build().start();
            Runtime.getRuntime().addShutdownHook(new Thread(s::shutdownNow));
            return s;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

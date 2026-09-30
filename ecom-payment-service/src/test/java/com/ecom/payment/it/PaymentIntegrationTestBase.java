package com.ecom.payment.it;

import static com.ecom.testing.RestCalls.exchange;
import static org.awaitility.Awaitility.await;

import com.ecom.common.events.EventEnvelope;
import com.ecom.common.events.EventTopics;
import com.ecom.common.events.Money;
import com.ecom.common.events.payload.InventoryReservedPayload;
import com.ecom.common.events.payload.OrderCancelledPayload;
import com.ecom.common.events.payload.OrderConfirmedPayload;
import com.ecom.payment.domain.PaymentTransaction;
import com.ecom.payment.domain.TransactionStatus;
import com.ecom.payment.domain.TransactionType;
import com.ecom.payment.infrastructure.persistence.PaymentTransactionRepository;
import com.ecom.payment.infrastructure.psp.FakePspClient;
import com.ecom.payment.infrastructure.psp.StripeSignatureVerifier;
import com.ecom.testing.ContainerConfigs;
import com.ecom.testing.Containers;
import com.ecom.testing.KafkaTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** One shared context (same config in every IT): Postgres + Kafka containers, fake PSP, fake peers. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.grpc.server.port=0",
            "ecom.payment.psp=fake",
            "ecom.outbox.poll-interval=100ms",
            "spring.profiles.active=plain-logs"
        })
@Import({ContainerConfigs.Postgres.class, ContainerConfigs.Kafka.class, PaymentItConfig.class})
@AutoConfigureRestTestClient
public abstract class PaymentIntegrationTestBase {

    protected static final Duration TIMEOUT = Duration.ofSeconds(30);
    protected static final String VISA = "pm_card_visa";
    protected static final String DECLINED = "pm_card_chargeDeclined";
    protected static final String THREE_DS = "pm_card_threeDSecure2Required";

    @Autowired protected FakePeers peers;
    @Autowired protected FakePspClient psp;
    @Autowired protected PaymentTransactionRepository transactions;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected RestTestClient rest;
    @Autowired protected StripeSignatureVerifier signer;

    protected String bootstrap() {
        return Containers.kafka().getBootstrapServers();
    }

    protected EventEnvelope sendInventoryReserved(UUID orderId) {
        return KafkaTestSupport.sendPayload(bootstrap(), mapper, EventTopics.INVENTORY_RESERVED, orderId.toString(),
                new InventoryReservedPayload(orderId.toString(), Instant.now().plusSeconds(900)));
    }

    protected void resend(String topic, UUID orderId, EventEnvelope envelope) {
        KafkaTestSupport.send(bootstrap(), mapper, topic, orderId.toString(), envelope);
    }

    protected EventEnvelope sendOrderCancelled(UUID orderId) {
        return KafkaTestSupport.sendPayload(bootstrap(), mapper, EventTopics.ORDER_CANCELLED, orderId.toString(),
                new OrderCancelledPayload(orderId.toString(), UUID.randomUUID().toString(),
                        OrderCancelledPayload.PAYMENT_TIMEOUT, List.of()));
    }

    protected EventEnvelope sendOrderConfirmed(UUID orderId) {
        return KafkaTestSupport.sendPayload(bootstrap(), mapper, EventTopics.ORDER_CONFIRMED, orderId.toString(),
                new OrderConfirmedPayload(orderId.toString(), UUID.randomUUID().toString(), List.of(),
                        Money.of("10.00", "EUR")));
    }

    protected Optional<PaymentTransaction> transaction(UUID orderId) {
        return transactions.findByOrderIdAndType(orderId, TransactionType.AUTHORIZATION);
    }

    protected PaymentTransaction awaitStatus(UUID orderId, TransactionStatus status) {
        return await().atMost(TIMEOUT).until(() -> transaction(orderId).orElse(null),
                tx -> tx != null && tx.status() == status);
    }

    protected void awaitProcessed(EventEnvelope envelope, String consumerGroup) {
        await().atMost(TIMEOUT).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ? AND consumer = ?",
                Integer.class, envelope.eventId(), consumerGroup) == 1);
    }

    /** Outbox rows are the source of truth for "was this event emitted" (relay is at-least-once). */
    protected int outboxCount(String topic, UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE topic = ? AND message_key = ?",
                Integer.class, topic, orderId.toString());
    }

    protected JsonNode awaitPublished(String topic, UUID orderId) {
        List<EventEnvelope> found = KafkaTestSupport.await(bootstrap(), mapper, topic,
                env -> orderId.toString().equals(env.payload().path("orderId").asString()), TIMEOUT);
        if (found.isEmpty()) {
            throw new AssertionError("No " + topic + " event for order " + orderId);
        }
        return found.get(0).payload();
    }

    protected ResponseEntity<String> postWebhook(byte[] body, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (signature != null) {
            headers.set(StripeSignatureVerifier.HEADER, signature);
        }
        return exchange(rest, HttpMethod.POST, "/webhooks/psp", body, headers, String.class);
    }

    protected ResponseEntity<String> completeAction(String pspReference, String outcome) {
        String path = "/dev/psp/complete-action/" + pspReference + "?outcome=" + outcome;
        return exchange(rest, HttpMethod.POST, path, null, new HttpHeaders(), String.class);
    }
}

package com.ecom.order.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecom.common.events.EventEnvelope;
import com.ecom.common.events.EventTopics;
import com.ecom.common.events.Money;
import com.ecom.common.events.payload.InventoryReservationFailedPayload;
import com.ecom.common.events.payload.InventoryReservedPayload;
import com.ecom.common.events.payload.PaymentAuthorizedPayload;
import com.ecom.common.events.payload.PaymentFailedPayload;
import com.ecom.common.events.payload.PaymentVoidedPayload;
import com.ecom.common.events.payload.ReservationExpiredPayload;
import com.ecom.testing.Containers;
import com.ecom.testing.KafkaTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderSagaIT extends AbstractOrderIT {

    private static final Money TOTAL = Money.of("35.81", "EUR");

    private EventEnvelope authorize(UUID id) {
        return send(EventTopics.PAYMENT_AUTHORIZED, id.toString(), new PaymentAuthorizedPayload(id.toString(), "tx-" + id, TOTAL));
    }

    private EventEnvelope expire(UUID id) {
        return send(EventTopics.RESERVATION_EXPIRED, id.toString(), new ReservationExpiredPayload(id.toString()));
    }

    private JsonNode singleOutboxPayload(String topic, UUID id) throws Exception {
        List<String> rows = jdbc.queryForList(
                "SELECT payload FROM outbox WHERE topic = ? AND message_key = ?", String.class, topic, id.toString());
        assertThat(rows).hasSize(1);
        return mapper.readTree(rows.getFirst()).path("payload");
    }

    @Test
    void inventoryReservedOnlyRecordsSagaStep() {
        UUID id = placeOrder(userWithCart());

        awaitProcessed(send(EventTopics.INVENTORY_RESERVED, id.toString(),
                new InventoryReservedPayload(id.toString(), Instant.now().plusSeconds(900))));

        assertThat(status(id)).isEqualTo("PENDING");
        assertThat(sagaStep(id)).isEqualTo("INVENTORY_RESERVED");
        assertThat(outboxCount(EventTopics.ORDER_CANCELLED, id) + outboxCount(EventTopics.ORDER_CONFIRMED, id)).isZero();
    }

    @Test
    void reservationFailureCancelsOutOfStock() throws Exception {
        String user = userWithCart();
        UUID id = placeOrder(user);

        awaitProcessed(send(EventTopics.INVENTORY_RESERVATION_FAILED, id.toString(),
                new InventoryReservationFailedPayload(id.toString(), List.of("SKU-A"))));

        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(cancelReason(id)).isEqualTo("OUT_OF_STOCK");
        JsonNode p = singleOutboxPayload(EventTopics.ORDER_CANCELLED, id);
        assertThat(p.path("reason").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(p.path("userId").asText()).isEqualTo(user);
        assertThat(p.path("lines")).hasSize(2);
    }

    @Test
    void paymentAuthorizedConfirmsAndEmitsOrderConfirmedOnKafka() {
        String user = userWithCart();
        UUID id = placeOrder(user);

        awaitProcessed(authorize(id));

        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(sagaStep(id)).isEqualTo("PAYMENT_AUTHORIZED");
        List<EventEnvelope> confirmed = KafkaTestSupport.await(Containers.kafka().getBootstrapServers(), mapper,
                EventTopics.ORDER_CONFIRMED, e -> id.toString().equals(e.payload().path("orderId").asText()), TIMEOUT);
        assertThat(confirmed).hasSize(1);
        JsonNode p = confirmed.getFirst().payload();
        assertThat(p.path("userId").asText()).isEqualTo(user);
        assertThat(p.path("lines")).hasSize(2);
        assertThat(p.path("total").path("units").asLong()).isEqualTo(35);
        assertThat(p.path("total").path("nanos").asInt()).isEqualTo(810_000_000);
        assertThat(p.path("total").path("currencyCode").asText()).isEqualTo("EUR");
    }

    @Test
    void paymentFailedCancelsPaymentDeclined() throws Exception {
        UUID id = placeOrder(userWithCart());

        awaitProcessed(send(EventTopics.PAYMENT_FAILED, id.toString(),
                new PaymentFailedPayload(id.toString(), "tx-1", "card_declined")));

        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(cancelReason(id)).isEqualTo("PAYMENT_DECLINED");
        assertThat(singleOutboxPayload(EventTopics.ORDER_CANCELLED, id).path("reason").asText()).isEqualTo("PAYMENT_DECLINED");
    }

    @Test
    void reservationExpiredCancelsPendingWithPaymentTimeout() throws Exception {
        UUID id = placeOrder(userWithCart());

        awaitProcessed(expire(id));

        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(cancelReason(id)).isEqualTo("PAYMENT_TIMEOUT");
        assertThat(singleOutboxPayload(EventTopics.ORDER_CANCELLED, id).path("reason").asText()).isEqualTo("PAYMENT_TIMEOUT");
    }

    @Test
    void authorizationThenExpiryConvergesOnConfirmed() {
        UUID id = placeOrder(userWithCart());

        awaitProcessed(authorize(id));
        awaitProcessed(expire(id));

        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(cancelReason(id)).isNull();
        assertThat(outboxCount(EventTopics.ORDER_CONFIRMED, id)).isEqualTo(1);
        assertThat(outboxCount(EventTopics.ORDER_CANCELLED, id)).isZero();
    }

    @Test
    void expiryThenLateAuthorizationConvergesOnCancelledWithoutConfirm() {
        UUID id = placeOrder(userWithCart());

        awaitProcessed(expire(id));
        awaitProcessed(authorize(id));

        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(cancelReason(id)).isEqualTo("PAYMENT_TIMEOUT");
        assertThat(sagaStep(id)).isEqualTo("PAYMENT_AUTHORIZED_LATE");
        assertThat(outboxCount(EventTopics.ORDER_CANCELLED, id)).isEqualTo(1);
        assertThat(outboxCount(EventTopics.ORDER_CONFIRMED, id)).isZero();
    }

    @Test
    void paymentVoidedOnlyRecordsSagaStep() {
        UUID id = placeOrder(userWithCart());
        awaitProcessed(expire(id));

        awaitProcessed(send(EventTopics.PAYMENT_VOIDED, id.toString(), new PaymentVoidedPayload(id.toString(), "tx-1")));

        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(sagaStep(id)).isEqualTo("PAYMENT_VOIDED");
        assertThat(outboxCount(EventTopics.ORDER_CANCELLED, id)).isEqualTo(1);
    }

    @Test
    void duplicateDeliveryOfSameEventEmitsOnce() {
        UUID id = placeOrder(userWithCart());
        EventEnvelope auth = authorize(id);
        awaitProcessed(auth);

        resend(EventTopics.PAYMENT_AUTHORIZED, id.toString(), auth);
        // a later event on the same key/partition proves the duplicate was consumed first
        awaitProcessed(send(EventTopics.PAYMENT_AUTHORIZED, id.toString(),
                new PaymentAuthorizedPayload(id.toString(), "tx-retry", TOTAL)));

        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(outboxCount(EventTopics.ORDER_CONFIRMED, id)).isEqualTo(1);
    }

    @Test
    void duplicateCancellationSignalsEmitOnce() {
        UUID id = placeOrder(userWithCart());
        EventEnvelope failed = send(EventTopics.PAYMENT_FAILED, id.toString(),
                new PaymentFailedPayload(id.toString(), "tx-1", "card_declined"));
        awaitProcessed(failed);

        resend(EventTopics.PAYMENT_FAILED, id.toString(), failed);
        awaitProcessed(send(EventTopics.PAYMENT_FAILED, id.toString(),
                new PaymentFailedPayload(id.toString(), "tx-1", "card_declined")));
        awaitProcessed(expire(id));

        assertThat(cancelReason(id)).isEqualTo("PAYMENT_DECLINED");
        assertThat(outboxCount(EventTopics.ORDER_CANCELLED, id)).isEqualTo(1);
    }

    @Test
    void eventForUnknownOrderIsIgnored() {
        UUID unknown = UUID.randomUUID();
        EventEnvelope env = authorize(unknown);
        awaitProcessed(env);
        assertThat(outboxCount(EventTopics.ORDER_CONFIRMED, unknown)).isZero();
    }
}

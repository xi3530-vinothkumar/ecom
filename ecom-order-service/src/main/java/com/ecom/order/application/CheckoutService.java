package com.ecom.order.application;

import com.ecom.catalog.proto.v1.ProductItem;
import com.ecom.common.events.EventTopics;
import com.ecom.common.events.Money;
import com.ecom.common.events.payload.OrderCreatedPayload;
import com.ecom.order.domain.CheckoutRequestHash;
import com.ecom.order.domain.Order;
import com.ecom.order.domain.OrderTotals;
import com.ecom.order.domain.PricedLine;
import com.ecom.order.domain.SagaStep;
import com.ecom.order.domain.ShippingAddress;
import com.ecom.order.infrastructure.OrderRepository;
import com.ecom.order.infrastructure.PeerClients;
import com.ecom.order.infrastructure.SagaStateRepository;
import com.ecom.platform.core.EcomException;
import com.ecom.platform.core.ErrorCode;
import com.ecom.platform.core.MoneyMapper;
import com.ecom.platform.kafka.OutboxWriter;
import com.ecom.user.proto.v1.Address;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * POST /orders. All peer gRPC calls happen before the DB transaction opens, so no connection is held
 * across network calls; the order, its lines, saga state and the order-created outbox row then commit
 * atomically.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final OrderRepository orders;
    private final SagaStateRepository sagaState;
    private final PeerClients peers;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final OrderProperties props;
    private final Clock clock;

    public CheckoutService(
            OrderRepository orders,
            SagaStateRepository sagaState,
            PeerClients peers,
            OutboxWriter outbox,
            TransactionTemplate tx,
            OrderProperties props,
            Clock clock) {
        this.orders = orders;
        this.sagaState = sagaState;
        this.peers = peers;
        this.outbox = outbox;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    public CheckoutResult checkout(CheckoutCommand cmd) {
        String requestHash = CheckoutRequestHash.of(cmd.paymentMethodRef(), cmd.shippingAddressId());

        Optional<CheckoutResult> replay = findReplay(cmd, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        List<PricedLine> lines = priceCart(cmd.userId());
        ShippingAddress address = snapshotAddress(cmd.userId(), cmd.shippingAddressId());
        Money shippingFee = Money.of(props.shippingFee(), props.currency());
        OrderTotals totals = OrderTotals.compute(lines, props.vatRate(), shippingFee);

        Order order = Order.placePending(cmd.userId(), cmd.idempotencyKey(), requestHash, cmd.paymentMethodRef(),
                address, lines, totals, Instant.now(clock));
        try {
            tx.executeWithoutResult(s -> persist(order));
        } catch (DataIntegrityViolationException e) {
            // lost the race against a concurrent request with the same Idempotency-Key
            return findReplay(cmd, requestHash).orElseThrow(() -> e);
        }
        log.info("Order {} placed PENDING for user {} (total {} {})",
                order.id(), cmd.userId(), totals.total().toBigDecimal().toPlainString(), totals.total().currencyCode());
        return new CheckoutResult(order.id(), order.status(), true);
    }

    private void persist(Order order) {
        orders.saveAndFlush(order);
        sagaState.record(order.id(), SagaStep.ORDER_CREATED, order.createdAt());
        outbox.publish(EventTopics.ORDER_CREATED, order.id().toString(),
                new OrderCreatedPayload(order.id().toString(), order.userId(), OrderEvents.lines(order)));
    }

    private Optional<CheckoutResult> findReplay(CheckoutCommand cmd, String requestHash) {
        return orders.findByUserIdAndIdempotencyKey(cmd.userId(), cmd.idempotencyKey()).map(existing -> {
            if (!existing.requestHash().equals(requestHash)) {
                throw EcomException.unprocessable("Idempotency-Key was already used with a different request body");
            }
            return new CheckoutResult(existing.id(), existing.status(), false);
        });
    }

    private List<PricedLine> priceCart(String userId) {
        // merge duplicate SKUs defensively; TreeMap keeps line order deterministic
        Map<String, Integer> quantities = new TreeMap<>();
        peers.getCart(userId).getLinesList().stream()
                .filter(l -> l.getQuantity() > 0)
                .forEach(l -> quantities.merge(l.getSku(), l.getQuantity(), Math::addExact));
        if (quantities.isEmpty()) {
            throw EcomException.unprocessable("Cart is empty");
        }

        Map<String, ProductItem> products = peers.getProducts(quantities.keySet()).stream()
                .collect(Collectors.toMap(ProductItem::getSku, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<String> unavailable = quantities.keySet().stream()
                .filter(sku -> !products.containsKey(sku) || !products.get(sku).getActive())
                .toList();
        if (!unavailable.isEmpty()) {
            throw EcomException.unprocessable("Products no longer available: " + String.join(", ", unavailable));
        }

        List<PricedLine> lines = new ArrayList<>(quantities.size());
        quantities.forEach((sku, qty) -> {
            ProductItem p = products.get(sku);
            if (!p.hasPrice()) {
                throw EcomException.unprocessable("Product " + sku + " has no price");
            }
            Money price = MoneyMapper.fromProto(p.getPrice());
            if (!props.currency().equals(price.currencyCode())) {
                throw EcomException.unprocessable("Product " + sku + " is priced in unsupported currency "
                        + price.currencyCode());
            }
            lines.add(new PricedLine(sku, displayName(p), price, qty));
        });
        return lines;
    }

    private static String displayName(ProductItem p) {
        return p.getVariantName().isBlank() ? p.getName() : p.getName() + " - " + p.getVariantName();
    }

    private ShippingAddress snapshotAddress(String userId, String addressId) {
        Address a;
        try {
            a = peers.getAddress(userId, addressId);
        } catch (EcomException e) {
            if (e.code() == ErrorCode.NOT_FOUND) {
                throw EcomException.unprocessable("Unknown shipping address " + addressId);
            }
            throw e;
        }
        if (!a.getUserId().isEmpty() && !a.getUserId().equals(userId)) {
            throw EcomException.unprocessable("Unknown shipping address " + addressId);
        }
        return new ShippingAddress(addressId, a.getLine1(), a.getLine2(), a.getCity(), a.getPostalCode(),
                a.getCountryCode());
    }
}

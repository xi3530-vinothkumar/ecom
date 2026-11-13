package com.ecom.order.application;

import com.ecom.order.domain.Order;
import com.ecom.order.domain.OrderStatus;
import com.ecom.order.infrastructure.OrderRepository;
import com.ecom.order.infrastructure.PeerClients;
import com.ecom.payment.proto.v1.PaymentTransaction;
import com.ecom.platform.core.EcomException;
import com.ecom.platform.core.ErrorCode;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderQueryService {

    private static final Logger log = LoggerFactory.getLogger(OrderQueryService.class);
    static final String REQUIRES_ACTION = "REQUIRES_ACTION";

    public record PaymentAction(String type, String clientSecret) {}

    private final OrderRepository orders;
    private final PeerClients peers;

    public OrderQueryService(OrderRepository orders, PeerClients peers) {
        this.orders = orders;
        this.peers = peers;
    }

    /** Owner-scoped lookup: someone else's order is indistinguishable from a missing one (404). */
    @Transactional(readOnly = true)
    public Order getOwned(String userId, String rawOrderId) {
        return parse(rawOrderId)
                .flatMap(orders::findWithLines)
                .filter(o -> o.userId().equals(userId))
                .orElseThrow(() -> EcomException.notFound("Order " + rawOrderId + " not found"));
    }

    /** Internal (gRPC) lookup, no owner check. */
    @Transactional(readOnly = true)
    public Order getInternal(String rawOrderId) {
        UUID id = parse(rawOrderId).orElseThrow(() -> EcomException.validation("Malformed order id " + rawOrderId));
        return orders.findWithLines(id).orElseThrow(() -> EcomException.notFound("Order " + rawOrderId + " not found"));
    }

    @Transactional(readOnly = true)
    public Page<Order> listMine(String userId, int page, int size) {
        return orders.findByUserId(userId, PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    }

    /**
     * 3DS/SCA challenge the browser must complete, if Payment reports one. Best effort: any failure to
     * reach Payment omits the action instead of failing the order read.
     */
    public Optional<PaymentAction> paymentAction(Order order) {
        if (order.status() != OrderStatus.PENDING) {
            return Optional.empty();
        }
        try {
            PaymentTransaction tx = peers.getTransaction(order.id().toString());
            if (REQUIRES_ACTION.equals(tx.getStatus()) && !tx.getClientSecret().isEmpty()) {
                return Optional.of(new PaymentAction("3ds", tx.getClientSecret()));
            }
        } catch (EcomException e) {
            if (e.code() != ErrorCode.NOT_FOUND) {
                log.warn("Payment lookup for order {} failed, omitting paymentAction: {}", order.id(), e.getMessage());
            }
        } catch (RuntimeException e) {
            log.warn("Payment lookup for order {} failed, omitting paymentAction", order.id(), e);
        }
        return Optional.empty();
    }

    private static Optional<UUID> parse(String raw) {
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException _) {
            return Optional.empty();
        }
    }
}

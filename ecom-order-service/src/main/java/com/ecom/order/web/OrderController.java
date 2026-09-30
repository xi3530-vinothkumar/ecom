package com.ecom.order.web;

import com.ecom.order.application.CheckoutCommand;
import com.ecom.order.application.CheckoutResult;
import com.ecom.order.application.CheckoutService;
import com.ecom.order.application.OrderQueryService;
import com.ecom.order.domain.Order;
import com.ecom.platform.core.CallContext;
import com.ecom.platform.core.EcomException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;
    private static final int MAX_PAGE_SIZE = 100;

    private final CheckoutService checkout;
    private final OrderQueryService queries;

    public OrderController(CheckoutService checkout, OrderQueryService queries) {
        this.checkout = checkout;
        this.queries = queries;
    }

    /** 202 for a newly placed order; 200 when an idempotent replay returns the existing one. */
    @PostMapping
    public ResponseEntity<CheckoutResponse> checkout(
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey, @Valid @RequestBody CheckoutRequest body) {
        String userId = CallContext.requireUserId();
        String key = idempotencyKey.trim();
        if (key.isEmpty() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw EcomException.validation(
                    IDEMPOTENCY_KEY + " must be 1-" + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        CheckoutResult result = checkout.checkout(
                new CheckoutCommand(userId, key, body.paymentMethodRef(), body.shippingAddressId()));
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(new CheckoutResponse(result.orderId(), result.status()));
    }

    @GetMapping("/{orderId}")
    public OrderView get(@PathVariable String orderId) {
        Order order = queries.getOwned(CallContext.requireUserId(), orderId);
        return OrderView.of(order, queries.paymentAction(order).orElse(null));
    }

    @GetMapping
    public PageView<OrderSummaryView> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw EcomException.validation("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        Page<Order> result = queries.listMine(CallContext.requireUserId(), page, size);
        return new PageView<>(result.map(OrderSummaryView::of).getContent(), page, size,
                result.getTotalElements(), result.getTotalPages());
    }
}

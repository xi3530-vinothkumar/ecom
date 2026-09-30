package com.ecom.cart.application;

import com.ecom.cart.config.CartProperties;
import com.ecom.cart.domain.CartOwner;
import com.ecom.cart.domain.Product;
import com.ecom.cart.infrastructure.CatalogClient;
import com.ecom.cart.infrastructure.RedisCartStore;
import com.ecom.common.events.Money;
import com.ecom.platform.core.EcomException;
import com.ecom.platform.core.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CartService {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);
    static final String DEFAULT_CURRENCY = "EUR";
    public static final int MAX_SKU_LENGTH = 64;

    private final RedisCartStore store;
    private final CatalogClient catalog;
    private final CartProperties props;

    public CartService(RedisCartStore store, CatalogClient catalog, CartProperties props) {
        this.store = store;
        this.catalog = catalog;
        this.props = props;
    }

    public CartView view(CartOwner owner) {
        Map<String, Integer> lines = store.lines(owner);
        Map<String, Product> products;
        boolean reachable = true;
        try {
            products = catalog.products(lines.keySet());
        } catch (EcomException e) {
            if (e.code() != ErrorCode.UNAVAILABLE) {
                throw e;
            }
            log.warn("Catalog unavailable; returning un-hydrated cart: {}", e.getMessage());
            products = Map.of();
            reachable = false;
        }

        List<CartView.Line> out = new ArrayList<>(lines.size());
        Money subtotal = null;
        int items = 0;
        for (Map.Entry<String, Integer> e : lines.entrySet()) {
            Product p = products.get(e.getKey());
            int qty = e.getValue();
            items += qty;
            boolean available = p != null && p.active() && p.price() != null;
            Money lineTotal = available ? p.price().times(qty) : null;
            if (lineTotal != null) {
                subtotal = subtotal == null ? lineTotal : subtotal.plus(lineTotal);
            }
            out.add(new CartView.Line(
                    e.getKey(),
                    p == null ? null : p.name(),
                    p == null ? null : p.variantName(),
                    qty,
                    p == null ? null : p.price(),
                    lineTotal,
                    available));
        }
        return new CartView(out, subtotal == null ? Money.zero(DEFAULT_CURRENCY) : subtotal, items, reachable);
    }

    public CartView setQuantity(CartOwner owner, String sku, int quantity) {
        validateSku(sku);
        if (quantity < 1 || quantity > props.maxQuantity()) {
            throw EcomException.validation("quantity must be between 1 and " + props.maxQuantity());
        }
        Product product = catalog.product(sku).orElseThrow(() -> EcomException.notFound("Unknown SKU " + sku));
        if (!product.active()) {
            throw EcomException.unprocessable("SKU " + sku + " is not available for sale");
        }
        if (!store.setQuantity(owner, sku, quantity)) {
            throw EcomException.unprocessable("Cart cannot hold more than " + props.maxLines() + " different items");
        }
        return view(owner);
    }

    public void remove(CartOwner owner, String sku) {
        validateSku(sku);
        store.remove(owner, sku);
    }

    public void clear(CartOwner owner) {
        store.clear(owner);
    }

    /** Raw lines for Order (no hydration: Order re-prices against Catalog itself). */
    public Map<String, Integer> rawLines(CartOwner.User user) {
        return store.lines(user);
    }

    public Map<String, Integer> merge(CartOwner.Guest guest, CartOwner.User user) {
        Map<String, Integer> merged = store.merge(guest, user);
        log.info("Merged guest cart {} into user {} ({} lines)", guest.guestId(), user.userId(), merged.size());
        return merged;
    }

    /** Removes what was just ordered, keeping anything added to the cart after checkout. */
    public long removeOrdered(CartOwner.User user, Map<String, Integer> ordered) {
        return store.decrement(user, ordered);
    }

    private static void validateSku(String sku) {
        if (sku == null || sku.isBlank() || sku.length() > MAX_SKU_LENGTH) {
            throw EcomException.validation("Invalid SKU");
        }
    }
}

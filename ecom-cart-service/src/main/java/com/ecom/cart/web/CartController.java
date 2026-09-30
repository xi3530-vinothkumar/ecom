package com.ecom.cart.web;

import com.ecom.cart.application.CartService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cart")
public class CartController {

    public record SetQuantityRequest(@NotNull Integer quantity) {}

    public record GuestCartResponse(String guestCartId) {}

    private final CartService carts;

    public CartController(CartService carts) {
        this.carts = carts;
    }

    /** Issues a new guest cart id; the cart itself is created on the first write. */
    @PostMapping("/guest")
    @ResponseStatus(HttpStatus.CREATED)
    public GuestCartResponse newGuestCart() {
        return new GuestCartResponse(UUID.randomUUID().toString());
    }

    @GetMapping
    public CartResponse get(@RequestHeader(value = CartOwnerResolver.GUEST_HEADER, required = false) String guest) {
        return CartResponse.of(carts.view(CartOwnerResolver.resolve(guest)));
    }

    @PutMapping("/items/{sku}")
    public CartResponse setQuantity(
            @RequestHeader(value = CartOwnerResolver.GUEST_HEADER, required = false) String guest,
            @PathVariable String sku,
            @Valid @RequestBody SetQuantityRequest body) {
        return CartResponse.of(carts.setQuantity(CartOwnerResolver.resolve(guest), sku, body.quantity()));
    }

    @DeleteMapping("/items/{sku}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(
            @RequestHeader(value = CartOwnerResolver.GUEST_HEADER, required = false) String guest,
            @PathVariable String sku) {
        carts.remove(CartOwnerResolver.resolve(guest), sku);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@RequestHeader(value = CartOwnerResolver.GUEST_HEADER, required = false) String guest) {
        carts.clear(CartOwnerResolver.resolve(guest));
    }
}

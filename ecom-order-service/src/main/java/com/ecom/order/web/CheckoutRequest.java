package com.ecom.order.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** paymentMethodRef is a PSP token created client-side; card data never reaches us (PCI SAQ-A). */
public record CheckoutRequest(
        @NotBlank @Size(max = 255) String paymentMethodRef,
        @NotBlank @Size(max = 64) String shippingAddressId) {}

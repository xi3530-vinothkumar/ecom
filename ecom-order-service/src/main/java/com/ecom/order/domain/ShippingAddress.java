package com.ecom.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Address snapshot taken at checkout; later edits to the user's address book do not affect the order. */
@Embeddable
public class ShippingAddress {

    @Column(name = "shipping_address_id", nullable = false)
    private String addressId;

    @Column(name = "ship_line1", nullable = false)
    private String line1;

    @Column(name = "ship_line2")
    private String line2;

    @Column(name = "ship_city", nullable = false)
    private String city;

    @Column(name = "ship_postal_code", nullable = false)
    private String postalCode;

    @Column(name = "ship_country_code", nullable = false, columnDefinition = "bpchar(2)")
    private String countryCode;

    protected ShippingAddress() {}

    public ShippingAddress(
            String addressId, String line1, String line2, String city, String postalCode, String countryCode) {
        this.addressId = addressId;
        this.line1 = line1;
        this.line2 = line2 == null || line2.isBlank() ? null : line2;
        this.city = city;
        this.postalCode = postalCode;
        this.countryCode = countryCode;
    }

    public String addressId() {
        return addressId;
    }

    public String line1() {
        return line1;
    }

    public String line2() {
        return line2;
    }

    public String city() {
        return city;
    }

    public String postalCode() {
        return postalCode;
    }

    public String countryCode() {
        return countryCode;
    }
}

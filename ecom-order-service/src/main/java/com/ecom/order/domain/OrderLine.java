package com.ecom.order.domain;

import com.ecom.common.events.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

@Embeddable
public class OrderLine {

    @Column(name = "sku", nullable = false)
    private String sku;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineTotal;

    protected OrderLine() {}

    static OrderLine from(PricedLine line) {
        OrderLine l = new OrderLine();
        l.sku = line.sku();
        l.name = line.name();
        l.unitPrice = MoneyColumns.toColumn(line.unitPrice());
        l.quantity = line.quantity();
        l.lineTotal = MoneyColumns.toColumn(line.lineTotal());
        return l;
    }

    public String sku() {
        return sku;
    }

    public String name() {
        return name;
    }

    public int quantity() {
        return quantity;
    }

    public Money unitPrice(String currency) {
        return MoneyColumns.fromColumn(unitPrice, currency);
    }

    public Money lineTotal(String currency) {
        return MoneyColumns.fromColumn(lineTotal, currency);
    }
}

package com.ecom.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationTemplateTest {

    private final NotificationTemplate template = new NotificationTemplate("ORDER_CONFIRMED", Channel.EMAIL,
            "Order ${orderId} confirmed", "Hi ${name}, you paid ${total} for ${orderId}.");

    @Test
    void substitutesAllPlaceholdersInSubjectAndBody() {
        var r = template.render(Map.of("orderId", "o-1", "name", "Ann", "total", "35.81 EUR"));
        assertThat(r.subject()).isEqualTo("Order o-1 confirmed");
        assertThat(r.body()).isEqualTo("Hi Ann, you paid 35.81 EUR for o-1.");
    }

    @Test
    void valuesAreInsertedLiterallyAndNeverReExpanded() {
        var r = template.render(Map.of("orderId", "o-1", "name", "${total} $1 \\", "total", "35.81 EUR"));
        assertThat(r.body()).isEqualTo("Hi ${total} $1 \\, you paid 35.81 EUR for o-1.");
    }

    @Test
    void missingVariableFails() {
        assertThatThrownBy(() -> template.render(Map.of("orderId", "o-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'name'");
    }

    @Test
    void textWithoutPlaceholdersIsUnchangedAndExtraVarsIgnored() {
        var t = new NotificationTemplate("WELCOME", Channel.EMAIL, "Welcome", "Costs $5, {not} a ${ placeholder");
        var r = t.render(Map.of("unused", "x"));
        assertThat(r.subject()).isEqualTo("Welcome");
        assertThat(r.body()).isEqualTo("Costs $5, {not} a ${ placeholder");
    }

    @Test
    void cancelledTemplateKeyFollowsReason() {
        assertThat(TemplateKeys.orderCancelled("PAYMENT_TIMEOUT")).isEqualTo("ORDER_CANCELLED_PAYMENT_TIMEOUT");
        assertThatThrownBy(() -> TemplateKeys.orderCancelled("x'; drop")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TemplateKeys.orderCancelled(null)).isInstanceOf(IllegalArgumentException.class);
    }
}

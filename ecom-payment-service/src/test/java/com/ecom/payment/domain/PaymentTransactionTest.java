package com.ecom.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecom.common.events.Money;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTransactionTest {

    private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");
    private static final Instant T1 = T0.plusSeconds(30);
    private static final Money AMOUNT = Money.of("25.90", "EUR");

    private PaymentTransaction requiresAction() {
        return PaymentTransaction.requiresAction(UUID.randomUUID(), AMOUNT, "pi_1", "pi_1_secret", T0);
    }

    @Test
    void requiresActionKeepsClientSecretUntilResolved() {
        var tx = requiresAction();
        assertThat(tx.clientSecret()).isEqualTo("pi_1_secret");
        tx.markAuthorized(T1);
        assertThat(tx.status()).isEqualTo(TransactionStatus.AUTHORIZED);
        assertThat(tx.clientSecret()).isNull();
        assertThat(tx.updatedAt()).isEqualTo(T1);
        assertThat(tx.amountMoney().toBigDecimal()).isEqualByComparingTo("25.90");
    }

    @Test
    void failureClearsSecretAndRecordsReason() {
        var tx = requiresAction();
        tx.markFailed("payment_intent_authentication_failure", T1);
        assertThat(tx.status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(tx.clientSecret()).isNull();
        assertThat(tx.failureReason()).isEqualTo("payment_intent_authentication_failure");
    }

    @Test
    void voidAllowedFromRequiresActionAndAuthorized() {
        var pending = requiresAction();
        pending.markVoided(T1);
        assertThat(pending.status()).isEqualTo(TransactionStatus.VOIDED);
        assertThat(pending.clientSecret()).isNull();

        var authorized = PaymentTransaction.authorized(UUID.randomUUID(), AMOUNT, "pi_2", T0);
        authorized.markVoided(T1);
        assertThat(authorized.status()).isEqualTo(TransactionStatus.VOIDED);
    }

    @Test
    void captureOnlyFromAuthorized() {
        var authorized = PaymentTransaction.authorized(UUID.randomUUID(), AMOUNT, "pi_3", T0);
        authorized.markCaptured(T1);
        assertThat(authorized.status()).isEqualTo(TransactionStatus.CAPTURED);

        assertThatThrownBy(() -> requiresAction().markCaptured(T1)).isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> authorized.markVoided(T1)).isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void terminalStatesRejectFurtherTransitions() {
        var failed = PaymentTransaction.failed(UUID.randomUUID(), AMOUNT, null, "card_declined", T0);
        assertThatThrownBy(() -> failed.markAuthorized(T1)).isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> failed.markVoided(T1)).isInstanceOf(IllegalTransitionException.class);

        var voided = requiresAction();
        voided.markVoided(T1);
        assertThatThrownBy(() -> voided.markAuthorized(T1)).isInstanceOf(IllegalTransitionException.class);
        assertThat(TransactionStatus.VOIDED.isVoidable()).isFalse();
        assertThat(TransactionStatus.AUTHORIZED.isVoidable()).isTrue();
    }
}

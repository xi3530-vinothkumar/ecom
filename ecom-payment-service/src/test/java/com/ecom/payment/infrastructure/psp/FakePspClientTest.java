package com.ecom.payment.infrastructure.psp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecom.payment.application.psp.AuthorizationResult;
import com.ecom.payment.application.psp.PspException;
import org.junit.jupiter.api.Test;

class FakePspClientTest {

    private final FakePspClient psp = new FakePspClient();

    @Test
    void visaAuthorizesDeterministicallyAndReplaysPerKey() {
        var first = psp.authorize("order-1", 1250, "EUR", "pm_card_visa");
        var again = psp.authorize("order-1", 1250, "EUR", "pm_card_visa");
        var otherPsp = new FakePspClient().authorize("order-1", 1250, "EUR", "pm_card_visa");

        assertThat(first).isInstanceOf(AuthorizationResult.Authorized.class);
        assertThat(again).isEqualTo(first);
        assertThat(otherPsp).isEqualTo(first);
        assertThat(first.card().brand()).isEqualTo("visa");
        assertThat(first.card().last4()).isEqualTo("4242");
        assertThat(psp.authorizeCalls("order-1")).isEqualTo(2);
        assertThat(psp.intent(((AuthorizationResult.Authorized) first).pspReference()))
                .hasValueSatisfying(i -> {
                    assertThat(i.status()).isEqualTo(FakePspClient.STATUS_REQUIRES_CAPTURE);
                    assertThat(i.orderId()).isEqualTo("order-1");
                    assertThat(i.currency()).isEqualTo("eur");
                });
    }

    @Test
    void differentKeysGetDifferentIntents() {
        var a = (AuthorizationResult.Authorized) psp.authorize("order-a", 100, "EUR", "pm_card_visa");
        var b = (AuthorizationResult.Authorized) psp.authorize("order-b", 100, "EUR", "pm_card_visa");
        assertThat(a.pspReference()).isNotEqualTo(b.pspReference()).startsWith("pi_fake_");
    }

    @Test
    void declineTokenIsDeclined() {
        var result = psp.authorize("order-2", 500, "EUR", "pm_card_chargeDeclined");
        assertThat(result).isInstanceOfSatisfying(AuthorizationResult.Declined.class, d -> {
            assertThat(d.reason()).isEqualTo("card_declined");
            assertThat(d.pspReference()).isNotNull();
        });
    }

    @Test
    void unknownTokenIsDeclinedWithoutIntent() {
        var result = psp.authorize("order-3", 500, "EUR", "pm_card_unknown");
        assertThat(result).isInstanceOfSatisfying(AuthorizationResult.Declined.class, d -> {
            assertThat(d.reason()).isEqualTo("invalid_payment_method");
            assertThat(d.pspReference()).isNull();
        });
    }

    @Test
    void threeDsRequiresActionThenCompletes() {
        var result = (AuthorizationResult.RequiresAction)
                psp.authorize("order-4", 999, "EUR", "pm_card_threeDSecure2Required");
        assertThat(result.clientSecret()).startsWith(result.pspReference() + "_secret_");
        assertThat(psp.authorize("order-4", 999, "EUR", "pm_card_threeDSecure2Required")).isEqualTo(result);

        var completed = psp.completeAction(result.pspReference(), true);
        assertThat(completed.status()).isEqualTo(FakePspClient.STATUS_REQUIRES_CAPTURE);
        assertThatThrownBy(() -> psp.completeAction(result.pspReference(), true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failedThreeDsRecordsAuthenticationFailure() {
        var result = (AuthorizationResult.RequiresAction)
                psp.authorize("order-5", 999, "EUR", "pm_card_threeDSecure2Required");
        var failed = psp.completeAction(result.pspReference(), false);
        assertThat(failed.status()).isEqualTo(FakePspClient.STATUS_REQUIRES_PAYMENT_METHOD);
        assertThat(failed.lastErrorCode()).isEqualTo(FakePspClient.AUTHENTICATION_FAILED);
    }

    @Test
    void reusingKeyWithDifferentParametersFails() {
        psp.authorize("order-6", 100, "EUR", "pm_card_visa");
        assertThatThrownBy(() -> psp.authorize("order-6", 200, "EUR", "pm_card_visa")).isInstanceOf(PspException.class);
    }

    @Test
    void cancelAndCaptureAreIdempotentAndGuarded() {
        var auth = (AuthorizationResult.Authorized) psp.authorize("order-7", 100, "EUR", "pm_card_visa");
        psp.capture(auth.pspReference());
        psp.capture(auth.pspReference());
        assertThat(psp.intent(auth.pspReference()).orElseThrow().status()).isEqualTo(FakePspClient.STATUS_SUCCEEDED);
        assertThat(psp.captureCalls(auth.pspReference())).isEqualTo(2);
        assertThatThrownBy(() -> psp.cancel(auth.pspReference())).isInstanceOf(PspException.class);

        var action = (AuthorizationResult.RequiresAction)
                psp.authorize("order-8", 100, "EUR", "pm_card_threeDSecure2Required");
        assertThatThrownBy(() -> psp.capture(action.pspReference())).isInstanceOf(PspException.class);
        psp.cancel(action.pspReference());
        psp.cancel(action.pspReference());
        assertThat(psp.intent(action.pspReference()).orElseThrow().status()).isEqualTo(FakePspClient.STATUS_CANCELED);
        assertThatThrownBy(() -> psp.completeAction(action.pspReference(), true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> psp.cancel("pi_missing")).isInstanceOf(PspException.class);
    }
}

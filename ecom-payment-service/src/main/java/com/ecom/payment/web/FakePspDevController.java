package com.ecom.payment.web;

import com.ecom.payment.application.PspWebhookService;
import com.ecom.payment.infrastructure.psp.FakePspClient;
import com.ecom.payment.infrastructure.psp.StripeSignatureVerifier;
import com.ecom.platform.core.EcomException;
import java.time.Clock;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Local/e2e stand-in for the customer finishing 3DS in the browser. Builds the Stripe-shaped event
 * the real PSP would send, signs it with the webhook secret and feeds it through the same
 * verify-then-process path as {@code POST /webhooks/psp}. Exists only when {@code PSP_MODE=fake}.
 */
@RestController
@ConditionalOnProperty(prefix = "ecom.payment", name = "psp", havingValue = "fake")
public class FakePspDevController {

    private final FakePspClient fakePsp;
    private final StripeSignatureVerifier signer;
    private final PspWebhookService webhooks;
    private final ObjectMapper mapper;
    private final Clock clock;

    public FakePspDevController(FakePspClient fakePsp, StripeSignatureVerifier signer, PspWebhookService webhooks,
            ObjectMapper mapper, Clock clock) {
        this.fakePsp = fakePsp;
        this.signer = signer;
        this.webhooks = webhooks;
        this.mapper = mapper;
        this.clock = clock;
    }

    @PostMapping("/dev/psp/complete-action/{pspReference}")
    public Map<String, Object> completeAction(@PathVariable String pspReference, @RequestParam String outcome) {
        boolean succeeded = switch (outcome) {
            case "succeeded" -> true;
            case "failed" -> false;
            default -> throw EcomException.validation("outcome must be succeeded or failed");
        };
        FakePspClient.Intent intent;
        try {
            intent = fakePsp.completeAction(pspReference, succeeded);
        } catch (NoSuchElementException e) {
            throw EcomException.notFound(e.getMessage());
        } catch (IllegalStateException e) {
            throw EcomException.conflict(e.getMessage());
        }
        String eventId = "evt_fake_" + UUID.randomUUID().toString().replace("-", "");
        String type = succeeded ? PspWebhookService.AMOUNT_CAPTURABLE_UPDATED : PspWebhookService.PAYMENT_FAILED;
        byte[] body = eventJson(eventId, type, intent);
        PspWebhookService.Outcome result = webhooks.receive(body, signer.sign(body));
        return Map.of("eventId", eventId, "type", type, "outcome", result.name());
    }

    public byte[] eventJson(String eventId, String type, FakePspClient.Intent intent) {
        return stripeEvent(mapper, eventId, type, intent, clock.instant().getEpochSecond());
    }

    /** Stripe {@code event} object wrapping a {@code payment_intent}. */
    public static byte[] stripeEvent(ObjectMapper mapper, String eventId, String type, FakePspClient.Intent intent,
            long createdEpochSecond) {
        ObjectNode event = mapper.createObjectNode()
                .put("id", eventId)
                .put("object", "event")
                .put("type", type)
                .put("created", createdEpochSecond)
                .put("livemode", false);
        ObjectNode object = event.putObject("data").putObject("object")
                .put("id", intent.id())
                .put("object", "payment_intent")
                .put("status", intent.status())
                .put("amount", intent.amountMinor())
                .put("amount_capturable", FakePspClient.STATUS_REQUIRES_CAPTURE.equals(intent.status()) ? intent.amountMinor() : 0)
                .put("currency", intent.currency())
                .put("capture_method", "manual")
                .put("payment_method", intent.paymentMethodRef());
        object.putObject("metadata").put("order_id", intent.orderId());
        if (intent.lastErrorCode() != null) {
            object.putObject("last_payment_error").put("type", "card_error").put("code", intent.lastErrorCode());
        } else {
            object.putNull("last_payment_error");
        }
        try {
            return mapper.writeValueAsBytes(event);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }
}

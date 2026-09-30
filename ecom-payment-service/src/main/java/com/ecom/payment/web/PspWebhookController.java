package com.ecom.payment.web;

import com.ecom.payment.application.PspWebhookService;
import com.ecom.payment.infrastructure.psp.StripeSignatureVerifier;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Public (no JWT): authenticity comes from the PSP signature, checked over the raw bytes. */
@RestController
public class PspWebhookController {

    private final PspWebhookService webhooks;

    public PspWebhookController(PspWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/webhooks/psp")
    public Map<String, Object> receive(
            @RequestBody byte[] rawBody,
            @RequestHeader(name = StripeSignatureVerifier.HEADER, required = false) String signature) {
        PspWebhookService.Outcome outcome = webhooks.receive(rawBody, signature);
        return Map.of("received", true, "outcome", outcome.name());
    }
}

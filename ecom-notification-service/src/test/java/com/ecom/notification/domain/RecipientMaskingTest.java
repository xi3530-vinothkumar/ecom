package com.ecom.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RecipientMaskingTest {

    @Test
    void emailKeepsFirstCharAndDomain() {
        assertThat(RecipientMasking.mask("john.doe@example.com")).isEqualTo("j***@example.com");
        assertThat(RecipientMasking.mask("a@example.com")).isEqualTo("*@example.com");
    }

    @Test
    void maskedEmailDoesNotRevealLocalPartLength() {
        assertThat(RecipientMasking.mask("jo@x.nl")).isEqualTo(RecipientMasking.mask("jonathan@x.nl"));
    }

    @Test
    void phoneKeepsLastTwoDigits() {
        assertThat(RecipientMasking.mask("+31612345678")).isEqualTo("**********78").doesNotContain("6123");
    }

    @Test
    void blankAndTinyValuesAreSafe() {
        assertThat(RecipientMasking.mask(null)).isEmpty();
        assertThat(RecipientMasking.mask("  ")).isEmpty();
        assertThat(RecipientMasking.mask("7")).isEqualTo("***");
    }

    @Test
    void outgoingNotificationToStringNeverLeaksRecipientOrBody() {
        var n = new OutgoingNotification(Channel.EMAIL, "secret.person@example.com", "WELCOME", "Hi", "private body");
        assertThat(n.toString()).doesNotContain("secret.person").doesNotContain("private body").contains("s***@example.com");
    }
}

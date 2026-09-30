package com.ecom.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpaqueTokensTest {

    @Test
    void tokensCarry256BitsAndAreUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String t = OpaqueTokens.generate();
            assertThat(Base64.getUrlDecoder().decode(t)).hasSize(32);
            assertThat(seen.add(t)).isTrue();
        }
    }

    @Test
    void hashIsStableLowercaseSha256Hex() {
        assertThat(OpaqueTokens.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}

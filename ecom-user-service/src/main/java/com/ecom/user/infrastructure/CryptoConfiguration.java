package com.ecom.user.infrastructure;

import com.ecom.platform.security.JwtIssuer;
import com.ecom.platform.security.JwtProperties;
import com.ecom.user.application.AuthProperties;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class CryptoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CryptoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Argon2id, 16 MiB memory, 2 iterations (Spring Security 5.8+ defaults). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    /**
     * Replaces the starter's issuer so an unset key fails fast with a clear message and so local
     * setups can opt into an ephemeral key (tokens become invalid on every restart).
     */
    @Bean
    JwtIssuer jwtIssuer(JwtProperties props, AuthProperties auth, Clock clock) {
        String pem = props.getPrivateKeyPem();
        if (auth.ephemeralJwtKey()) {
            log.warn("JWT_EPHEMERAL_KEY=true: signing with a freshly generated RSA key (kid={}). "
                    + "Issued tokens will not survive a restart and replicas will not share keys. "
                    + "Never use this outside local/Compose.", props.getKeyId());
            pem = generateRsaPkcs8Pem();
        } else if (pem == null || pem.isBlank()) {
            throw new IllegalStateException(
                    "No JWT signing key: set JWT_PRIVATE_KEY_PEM (PKCS#8 RSA) or JWT_EPHEMERAL_KEY=true for local use");
        }
        if (props.getKeyId() == null || props.getKeyId().isBlank()) {
            throw new IllegalStateException("JWT_KEY_ID must be set");
        }
        // env vars frequently carry PEMs with literal "\n" escapes
        props.setPrivateKeyPem(pem.replace("\\n", "\n"));
        return new JwtIssuer(props, clock);
    }

    private static String generateRsaPkcs8Pem() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            byte[] pkcs8 = gen.generateKeyPair().getPrivate().getEncoded();
            return "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pkcs8)
                    + "\n-----END PRIVATE KEY-----\n";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA unavailable", e);
        }
    }
}

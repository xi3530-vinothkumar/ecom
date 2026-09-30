package com.ecom.platform.security;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Minimal PEM parsing for RSA keys (PKCS#8 private, X.509 SubjectPublicKeyInfo public). */
public final class PemKeys {

    public static RSAPrivateCrtKey privateKey(String pem) {
        try {
            byte[] der = decode(pem, "PRIVATE KEY");
            return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid PKCS#8 RSA private key PEM", e);
        }
    }

    public static RSAPublicKey publicKey(String pem) {
        try {
            byte[] der = decode(pem, "PUBLIC KEY");
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid X.509 RSA public key PEM", e);
        }
    }

    public static RSAPublicKey publicKeyOf(RSAPrivateCrtKey key) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] decode(String pem, String type) {
        String body = pem.replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private PemKeys() {}
}

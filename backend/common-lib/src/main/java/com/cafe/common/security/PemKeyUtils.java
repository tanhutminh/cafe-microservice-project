package com.cafe.common.security;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Parses PKCS8 private / X.509 public PEM text (as distributed via config-server) into
 * java.security key objects for RS256 JWT signing/verification.
 */
public final class PemKeyUtils {

    private PemKeyUtils() {
    }

    public static PublicKey parsePublicKey(String pem) {
        try {
            byte[] der = Base64.getDecoder().decode(stripPemHeaders(pem));
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePublic(new X509EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalArgumentException("Invalid RSA public key PEM", e);
        }
    }

    public static PrivateKey parsePrivateKey(String pem) {
        try {
            byte[] der = Base64.getDecoder().decode(stripPemHeaders(pem));
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalArgumentException("Invalid RSA private key PEM", e);
        }
    }

    private static String stripPemHeaders(String pem) {
        return pem
                .replaceAll("-----BEGIN (.*)-----", "")
                .replaceAll("-----END (.*)-----", "")
                .replaceAll("\\s", "");
    }
}

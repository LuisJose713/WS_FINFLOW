package com.softdevcode.ws_finflow.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;

/**
 * Only validates transport encoding and AEAD byte sizes.
 * The API NEVER handles encryption keys or plaintext.
 * The client MUST authenticate AES-GCM ciphertext with its local key.
 */
public final class VaultEnvelopeValidator {
    public static final int NONCE_BYTES = 12;
    public static final int MAX_CIPHERTEXT_BYTES = 524288;
    private VaultEnvelopeValidator() {}

    public record Envelope(byte[] nonce, byte[] ciphertext) {}

    public static Envelope decode(String nonce, String ciphertext) {
        byte[] iv = decodeBytes(nonce, 16);
        byte[] encrypted = decodeBytes(ciphertext, 699052);
        if (iv.length != NONCE_BYTES || encrypted.length < 16 ||
                encrypted.length > MAX_CIPHERTEXT_BYTES) {
            throw invalid();
        }
        return new Envelope(iv, encrypted);
    }

    private static byte[] decodeBytes(String encoded, int maxChars) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > maxChars
                || !encoded.matches("[A-Za-z0-9_-]+")) throw invalid();
        try {
            return Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sobre cifrado inválido");
    }
}

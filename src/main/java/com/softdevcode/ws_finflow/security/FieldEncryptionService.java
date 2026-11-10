package com.softdevcode.ws_finflow.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Versioned encryption primitive for future sensitive database fields.
 *
 * NOT wired into entities yet: a deliberate, reversible migration of existing
 * rows and a server-managed secret are required before encrypting finance data.
 *
 * This protects database ciphertext, not browser-visible API responses.
 */
@Service
public final class FieldEncryptionService {
    private static final String PREFIX = "enc:v1:";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public FieldEncryptionService(
            @Value("${FINFLOW_FIELD_ENCRYPTION_KEY_BASE64:}") String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            // Safe default: no encryption can occur without managed key.
            this.key = null;
            return;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encodedKey.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid field encryption key encoding");
        }
        if (bytes.length != KEY_BYTES) {
            Arrays.fill(bytes, (byte) 0);
            throw new IllegalArgumentException("Field encryption key must be 256 bits");
        }
        this.key = new SecretKeySpec(bytes, "AES");
        Arrays.fill(bytes, (byte) 0);
    }

    public String encrypt(String plaintext, String field, long userId) {
        requireContext(plaintext, field, userId);
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context(field, userId));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(ciphertext, 0, payload, nonce.length, ciphertext.length);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Sensitive data encryption failed", ex);
        }
    }

    public String decrypt(String payload, String field, long userId) {
        requireContext(payload, field, userId);
        if (!payload.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Unsupported or plaintext field format");
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(payload.substring(PREFIX.length()));
            if (raw.length < NONCE_BYTES + TAG_BITS / 8) {
                throw new IllegalArgumentException("Invalid encrypted field");
            }
            byte[] nonce = Arrays.copyOfRange(raw, 0, NONCE_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(raw, NONCE_BYTES, raw.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context(field, userId));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (AEADBadTagException ex) {
            throw new IllegalArgumentException("Encrypted field integrity validation failed");
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            if (ex instanceof IllegalArgumentException) throw (IllegalArgumentException) ex;
            throw new IllegalStateException("Sensitive data decryption failed", ex);
        }
    }

    private SecretKeySpec requireKey() {
        if (key == null) throw new IllegalStateException("Field encryption is not configured");
        return key;
    }

    private static void requireContext(String text, String field, long userId) {
        if (text == null || field == null || field.isBlank() || userId <= 0) {
            throw new IllegalArgumentException("Invalid encryption context");
        }
    }

    private static byte[] context(String field, long userId) {
        return ("finflow:" + field + ":" + userId).getBytes(StandardCharsets.UTF_8);
    }
}

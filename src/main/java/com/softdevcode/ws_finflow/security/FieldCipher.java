package com.softdevcode.ws_finflow.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/** Application-side authenticated encryption. The database never receives the key. */
@Component("fieldCipher")
public final class FieldCipher {
    public static final String PREFIX = "ffenc:v1:";
    private final SecretKeySpec key;
    private final boolean enabled;
    private final SecureRandom random = new SecureRandom();

    public FieldCipher(@Value("${FINFLOW_FIELD_ENCRYPTION_KEY_BASE64:}") String encoded,
                       @Value("${FINFLOW_FIELD_ENCRYPTION_ENABLED:false}") boolean enabled) {
        this.enabled = enabled;
        byte[] raw;
        try { raw = encoded == null || encoded.isBlank() ? null : Base64.getDecoder().decode(encoded.trim()); }
        catch (IllegalArgumentException e) { throw new IllegalStateException("Invalid encryption key encoding"); }
        if (raw != null && raw.length != 32) throw new IllegalStateException("Encryption requires a 256-bit key");
        if (enabled && raw == null) throw new IllegalStateException("Encryption key is required");
        key = raw == null ? null : new SecretKeySpec(raw, "AES");
        if (raw != null) Arrays.fill(raw, (byte) 0);
    }

    public boolean enabled() { return enabled; }

    public String encrypt(String field, String value) {
        if (value == null || !enabled) return value;
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(field));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, envelope, 0, nonce.length);
            System.arraycopy(encrypted, 0, envelope, nonce.length, encrypted.length);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Field encryption failed"); }
    }

    public String decrypt(String field, String value) {
        if (value == null) return null;
        if (!value.startsWith(PREFIX)) {
            if (enabled) throw new IllegalStateException("Unencrypted protected field: " + field);
            return value;
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(value.substring(PREFIX.length()));
            if (raw.length < 28) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(128, raw, 0, 12));
            cipher.updateAAD(aad(field));
            return new String(cipher.doFinal(raw, 12, raw.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Protected field authentication failed: " + field);
        }
    }

    /** Independent HMAC purpose; never stores a searchable value or an unkeyed hash. */
    public String lookup(String field, String value) {
        if (value == null) return null;
        if (!enabled) return value;
        try {
            Mac derive = Mac.getInstance("HmacSHA256");
            derive.init(new SecretKeySpec(requireKey().getEncoded(), "HmacSHA256"));
            byte[] lookupKey = derive.doFinal(aad("lookup-key"));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(lookupKey, "HmacSHA256"));
            Arrays.fill(lookupKey, (byte) 0);
            mac.update(aad(field)); mac.update((byte) 0);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Protected lookup failed"); }
    }

    private SecretKeySpec requireKey() {
        if (key == null) throw new IllegalStateException("Encryption key is unavailable");
        return key;
    }
    private byte[] aad(String field) { return ("finflow-storage-v1:" + field).getBytes(StandardCharsets.UTF_8); }
}

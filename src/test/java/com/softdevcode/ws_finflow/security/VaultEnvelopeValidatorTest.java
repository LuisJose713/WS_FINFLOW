package com.softdevcode.ws_finflow.security;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class VaultEnvelopeValidatorTest {
    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Test void acceptsOpaqueGcmEnvelopeAndNeverNeedsPlaintext() {
        var decoded = VaultEnvelopeValidator.decode(encode(new byte[12]), encode(new byte[32]));
        assertEquals(12, decoded.nonce().length);
        assertEquals(32, decoded.ciphertext().length);
    }

    @Test void rejectsMissingOrMalformedNonceAndTruncatedTag() {
        assertThrows(ResponseStatusException.class, () ->
                VaultEnvelopeValidator.decode("plain text", encode(new byte[32])));
        assertThrows(ResponseStatusException.class, () ->
                VaultEnvelopeValidator.decode(encode(new byte[11]), encode(new byte[32])));
        assertThrows(ResponseStatusException.class, () ->
                VaultEnvelopeValidator.decode(encode(new byte[12]), encode(new byte[15])));
        assertThrows(ResponseStatusException.class, () ->
                VaultEnvelopeValidator.decode(encode(new byte[12]), "not-base64!"));
    }

    @Test void rejectsOversizedPayload() {
        assertThrows(ResponseStatusException.class, () ->
                VaultEnvelopeValidator.decode(encode(new byte[12]), encode(new byte[524289])));
    }
}

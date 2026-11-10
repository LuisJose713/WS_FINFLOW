package com.softdevcode.ws_finflow.security;

import org.junit.jupiter.api.Test;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class FieldEncryptionServiceTest {
    private FieldEncryptionService service() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return new FieldEncryptionService(Base64.getEncoder().encodeToString(random));
    }

    @Test void encryptsWithoutExposingPlaintextAndRecoversUnicode() {
        FieldEncryptionService encryption = service();
        String original = "Ahorro para mañana · $1.500.000";
        String ciphertext = encryption.encrypt(original, "transaction.description", 42);
        assertTrue(ciphertext.startsWith("enc:v1:"));
        assertFalse(ciphertext.contains(original));
        assertEquals(original, encryption.decrypt(ciphertext, "transaction.description", 42));
    }

    @Test void aDifferentNonceIsUsedForEveryEncryption() {
        FieldEncryptionService encryption = service();
        String first = encryption.encrypt("secret", "goal.note", 42);
        String second = encryption.encrypt("secret", "goal.note", 42);
        assertNotEquals(first, second);
        assertEquals("secret", encryption.decrypt(second, "goal.note", 42));
    }

    @Test void ciphertextCannotBeReplayedAcrossUsersOrFields() {
        FieldEncryptionService encryption = service();
        String ciphertext = encryption.encrypt("secret", "goal.note", 42);
        assertThrows(IllegalArgumentException.class,
                () -> encryption.decrypt(ciphertext, "goal.note", 99));
        assertThrows(IllegalArgumentException.class,
                () -> encryption.decrypt(ciphertext, "user.email", 42));
    }

    @Test void tamperingIsRejectedByAuthenticationTag() {
        FieldEncryptionService encryption = service();
        String ciphertext = encryption.encrypt("secret", "goal.note", 42);
        String corrupted = ciphertext.substring(0, ciphertext.length() - 1) +
                (ciphertext.endsWith("A") ? "B" : "A");
        assertThrows(IllegalArgumentException.class,
                () -> encryption.decrypt(corrupted, "goal.note", 42));
    }

    @Test void doesNotSilentlyEncryptWithoutServerManagedKey() {
        FieldEncryptionService unconfigured = new FieldEncryptionService("");
        assertThrows(IllegalStateException.class,
                () -> unconfigured.encrypt("text", "transaction.note", 42));
        assertThrows(IllegalArgumentException.class,
                () -> unconfigured.decrypt("text", "transaction.note", 42));
        assertThrows(IllegalArgumentException.class,
                () -> new FieldEncryptionService("not-base64!"));
    }
}

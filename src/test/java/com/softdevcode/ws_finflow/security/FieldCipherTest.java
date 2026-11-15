package com.softdevcode.ws_finflow.security;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import java.security.SecureRandom;
import static org.junit.jupiter.api.Assertions.*;

class FieldCipherTest {
    private FieldCipher cipher() {
        byte[] key=new byte[32];new SecureRandom().nextBytes(key);
        return new FieldCipher(Base64.getEncoder().encodeToString(key),true);
    }
    @Test void protectsUnicodeNullEmptyAndLiteralEncryptionPrefix() {
        var c=cipher();
        for(String value:new String[]{null,"","José · ahorro 💰","ffenc:v1:literal"})
            assertEquals(value,c.decrypt("users.username",c.encrypt("users.username",value)));
    }
    @Test void usesFreshNoncesAndRejectsWrongKeyContextAndTampering() {
        var c=cipher();String payload=c.encrypt("transactions.amount","123456.78");
        assertNotEquals(payload,c.encrypt("transactions.amount","123456.78"));
        assertThrows(IllegalStateException.class,()->cipher().decrypt("transactions.amount",payload));
        assertThrows(IllegalStateException.class,()->c.decrypt("goals.target_amount",payload));
        char replacement=payload.charAt(30)=='A'?'B':'A';
        String changed=payload.substring(0,30)+replacement+payload.substring(31);
        assertThrows(IllegalStateException.class,()->c.decrypt("transactions.amount",changed));
    }
    @Test void neverFallsBackToPlaintextWhenEnabled() {
        var c=cipher();
        assertThrows(IllegalStateException.class,()->c.decrypt("users.email","private@example.invalid"));
        assertThrows(IllegalStateException.class,()->new FieldCipher("",true));
        assertThrows(IllegalStateException.class,()->new FieldCipher("YWJj",true));
        assertThrows(IllegalStateException.class,()->c.decrypt("users.email","ffenc:v1:bad"));
    }
    @Test void keyedIndexesAreStablePurposeSeparatedAndKeyDependent() {
        var c=cipher();String index=c.lookup("users.email","test@example.invalid");
        assertEquals(index,c.lookup("users.email","test@example.invalid"));
        assertNotEquals(index,c.lookup("users.username","test@example.invalid"));
        assertNotEquals(index,cipher().lookup("users.email","test@example.invalid"));
        assertEquals(64,index.length());
    }
    @Test void exactMonetaryValuesSurviveConverters() {
        var c=cipher();var decimal=new EncryptedFields.BudgetLimit(c);var floating=new EncryptedFields.TransactionAmount(c);
        java.math.BigDecimal amount=new java.math.BigDecimal("9999999999999.99");
        assertEquals(amount,decimal.convertToEntityAttribute(decimal.convertToDatabaseColumn(amount)));
        assertEquals(0.01,floating.convertToEntityAttribute(floating.convertToDatabaseColumn(0.01)));
    }
}

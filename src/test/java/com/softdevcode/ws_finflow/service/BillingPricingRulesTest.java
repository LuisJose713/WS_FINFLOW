package com.softdevcode.ws_finflow.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BillingPricingRulesTest {
    @Test void percentPromotionUsesIntegerCop() {
        assertEquals(7920, BillingPricingRules.discountedPrice(9900,"PERCENT",20));
        assertEquals(990, BillingPricingRules.discountedPrice(9900,"PERCENT",90));
    }
    @Test void fixedPromotionNeverCreatesNegativeOrFreePrice() {
        assertEquals(7900, BillingPricingRules.discountedPrice(9900,"FIXED",2000));
        assertThrows(IllegalArgumentException.class,
                () -> BillingPricingRules.discountedPrice(9900,"FIXED",9900));
    }
    @Test void invalidTypeAndPercentageRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BillingPricingRules.discountedPrice(9900,"PERCENT",100));
        assertThrows(IllegalArgumentException.class,
                () -> BillingPricingRules.discountedPrice(9900,"unknown",20));
    }
}

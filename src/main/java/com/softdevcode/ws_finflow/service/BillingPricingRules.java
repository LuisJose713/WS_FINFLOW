package com.softdevcode.ws_finflow.service;

public final class BillingPricingRules {
    private BillingPricingRules() {}

    public static long discountedPrice(long priceCop, String type, long value) {
        if (priceCop < 1000 || priceCop > 10_000_000) throw new IllegalArgumentException("Precio fuera de rango");
        if ("PERCENT".equals(type)) {
            if (value < 1 || value > 90) throw new IllegalArgumentException("Porcentaje entre 1 y 90");
            return priceCop - (priceCop * value / 100);
        }
        if ("FIXED".equals(type)) {
            if (value < 1 || value >= priceCop) throw new IllegalArgumentException("Descuento fijo inválido");
            return priceCop - value;
        }
        throw new IllegalArgumentException("Tipo de descuento inválido");
    }
}

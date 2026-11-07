package com.softdevcode.ws_finflow.dto;

import java.math.BigDecimal;

public record BudgetOverview(
    Long id, Long categoryId, String categoryName, Integer year, Integer month,
    BigDecimal limitAmount, BigDecimal spent, BigDecimal remaining, Integer percentUsed,
    String status, String currency
) {}

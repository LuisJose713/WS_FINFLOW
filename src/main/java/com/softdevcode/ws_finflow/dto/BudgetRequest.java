package com.softdevcode.ws_finflow.dto;

import java.math.BigDecimal;

public record BudgetRequest(Long categoryId, Integer year, Integer month, BigDecimal amount) {}

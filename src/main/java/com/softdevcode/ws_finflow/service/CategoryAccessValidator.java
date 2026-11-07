package com.softdevcode.ws_finflow.service;

import com.softdevcode.ws_finflow.entity.Category;
import com.softdevcode.ws_finflow.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class CategoryAccessValidator {
    private final CategoryRepository categories;
    public void requireVisible(Integer userId, Long categoryId, String transactionType) {
        if (categoryId == null) return;
        Category category = categories.findById(categoryId)
                .filter(c -> c.getUserId() == null || c.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Categoría no disponible"));
        String expected = "income".equalsIgnoreCase(transactionType) ? "Income" : "Expense";
        if (!expected.equalsIgnoreCase(category.getType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La categoría no corresponde al tipo");
        }
    }
}

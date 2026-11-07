package com.softdevcode.ws_finflow.service;

import com.softdevcode.ws_finflow.entity.Category;
import com.softdevcode.ws_finflow.repository.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CategoryAccessValidatorTest {
    @Mock CategoryRepository categories;
    @InjectMocks CategoryAccessValidator validator;

    @Test void acceptsSystemCategory() {
        Category cat = new Category();
        cat.setType("Expense");
        when(categories.findById(2L)).thenReturn(Optional.of(cat));
        assertDoesNotThrow(() -> validator.requireVisible(8, 2L, "expense"));
    }

    @Test void rejectsAnotherUsersCategory() {
        Category cat = new Category();
        cat.setUserId(7);
        cat.setType("Expense");
        when(categories.findById(3L)).thenReturn(Optional.of(cat));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> validator.requireVisible(8, 3L, "expense")).getStatusCode());
    }

    @Test void refusesIncomeCategoryForExpenses() {
        Category cat = new Category();
        cat.setUserId(8);
        cat.setType("Income");
        when(categories.findById(3L)).thenReturn(Optional.of(cat));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> validator.requireVisible(8, 3L, "expense")).getStatusCode());
    }
}

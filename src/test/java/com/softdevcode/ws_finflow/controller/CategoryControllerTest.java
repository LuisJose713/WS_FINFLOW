package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.dto.CategoryRequest;
import com.softdevcode.ws_finflow.entity.Category;
import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.CategoryRepository;
import com.softdevcode.ws_finflow.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CategoryControllerTest {
    @Mock CategoryRepository categories;
    @Mock UserRepository users;
    @Mock Authentication auth;
    @InjectMocks CategoryController controller;

    private void owner() {
        User user = new User();
        user.setId(7L);
        when(auth.getName()).thenReturn("owner@example.com");
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
    }

    private CategoryRequest request(String name, String type) {
        CategoryRequest request = new CategoryRequest();
        request.setName(name);
        request.setType(type);
        return request;
    }

    @Test void createsOnlyForAuthenticatedOwner() {
        owner();
        Category created = new Category();
        created.setUserId(7);
        created.setName("Mascotas");
        created.setType("Expense");
        when(categories.saveAndFlush(any(Category.class))).thenAnswer(a -> a.getArgument(0));
        var response = controller.create(auth, request(" Mascotas ", "expense"));
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(7, response.getBody().getUserId());
        assertEquals("Mascotas", response.getBody().getName());
        assertEquals("Expense", response.getBody().getType());
    }

    @Test void cannotEditGlobalOrOtherUsersCategories() {
        owner();
        when(categories.findByIdAndUserId(42L, 7)).thenReturn(Optional.empty());
        var error = assertThrows(ResponseStatusException.class,
                () -> controller.update(auth, 42L, request("Robada", "Income")));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(categories, never()).saveAndFlush(any());
    }

    @Test void cannotDeleteCategoryBelongingToSomeoneElse() {
        owner();
        when(categories.findByIdAndUserId(42L, 7)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> controller.delete(auth, 42L)).getStatusCode());
        verify(categories, never()).delete(any());
    }

    @Test void forbidsDuplicateCategoryNamesForSameUser() {
        owner();
        when(categories.existsByUserIdAndNameIgnoreCaseAndTypeIgnoreCase(7, "Mascotas", "Expense"))
            .thenReturn(true);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> controller.create(auth, request("Mascotas", "Expense"))).getStatusCode());
    }
}

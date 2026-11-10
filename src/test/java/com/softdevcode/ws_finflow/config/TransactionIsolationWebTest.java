package com.softdevcode.ws_finflow.config;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.security.JwtUtil;
import com.softdevcode.ws_finflow.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security regression tests for authenticated user isolation at the HTTP layer.
 * Never query or mutate another person's production data to run these tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransactionIsolationWebTest {
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwtUtil;
    @MockBean UserRepository users;
    @MockBean TransactionService transactions;

    @Test void ignoresSpoofedUserIdHeaderAndUsesVerifiedToken() throws Exception {
        User owner = new User();
        owner.setId(42L);
        owner.setEmail("owner@finflow.test");
        owner.setRole("USER");
        owner.setTokenVersion(0);
        when(users.findByEmail(owner.getEmail())).thenReturn(Optional.of(owner));
        when(transactions.getRecent(42, 5)).thenReturn(List.of());

        mvc.perform(get("/transactions/recent")
                .header("Authorization", "Bearer " + jwtUtil.generateToken(owner.getEmail(), false, 0))
                .header("X-User-Id", "99999"))
                .andExpect(status().isOk());

        verify(transactions).getRecent(42, 5);
        verify(transactions, never()).getRecent(eq(99999), anyInt());
    }

    @Test void anonymousCallerCannotReadTransactionsEvenWithForgedUserHeader() throws Exception {
        mvc.perform(get("/transactions/recent")
                .header("X-User-Id", "42"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(transactions);
    }
}

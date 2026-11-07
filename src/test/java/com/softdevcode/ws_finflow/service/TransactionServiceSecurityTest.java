package com.softdevcode.ws_finflow.service;

import com.softdevcode.ws_finflow.dto.TransactionRequest;
import com.softdevcode.ws_finflow.entity.Transaction;
import com.softdevcode.ws_finflow.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
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
class TransactionServiceSecurityTest {
    @Mock TransactionRepository repository;
    @Mock EntityManager entityManager;
    @InjectMocks TransactionService service;

    @Test
    void cannotDeleteAnotherUsersTransaction() {
        when(repository.findByIdAndUserId(99L, 1)).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.delete(1, 99L));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(repository, never()).delete(any(Transaction.class));
        verify(repository, never()).deleteById(anyLong());
    }

    @Test
    void canDeleteOwnTransaction() {
        Transaction own = new Transaction();
        own.setId(99L);
        own.setUserId(1);
        when(repository.findByIdAndUserId(99L, 1)).thenReturn(Optional.of(own));
        service.delete(1, 99L);
        verify(repository).delete(own);
    }

    @Test
    void cannotEditAnotherUsersTransaction() {
        when(repository.findByIdAndUserId(99L, 1)).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.update(1, 99L, new TransactionRequest()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsInvalidAmountForOwnedTransaction() {
        Transaction own = new Transaction();
        own.setId(99L);
        own.setUserId(1);
        when(repository.findByIdAndUserId(99L, 1)).thenReturn(Optional.of(own));
        TransactionRequest request = new TransactionRequest();
        request.setAmount(-10.0);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.update(1, 99L, request));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(repository, never()).save(any());
    }
}

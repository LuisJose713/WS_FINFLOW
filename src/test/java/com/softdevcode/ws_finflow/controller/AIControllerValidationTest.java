package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.service.AIService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AIControllerValidationTest {
    @Mock AIService aiService;
    @Mock UserRepository users;
    @Mock Authentication authentication;
    @InjectMocks AIController controller;

    @Test void rejectsNonStringChatMessageBeforeCallingService() {
        assertEquals(HttpStatus.BAD_REQUEST,
                controller.chat(authentication, Map.of("mensaje", Map.of("nested", "payload"))).getStatusCode());
        verifyNoInteractions(aiService, users);
    }

    @Test void rejectsExcessiveChatMessageBeforeCallingService() {
        assertEquals(HttpStatus.BAD_REQUEST,
                controller.chat(authentication, Map.of("mensaje", "x".repeat(4097))).getStatusCode());
        verifyNoInteractions(aiService, users);
    }

    @Test void rejectsNonFiniteAndNonPositiveMonetaryAmounts() {
        for (Double value : new Double[]{ Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0 }) {
            assertEquals(HttpStatus.BAD_REQUEST, controller.getMeta(authentication, "Ahorro", value).getStatusCode());
        }
        verifyNoInteractions(aiService, users);
    }

    @Test void acceptsValidChatMessageForAuthenticatedUser() {
        User user = new User();
        user.setId(22L);
        user.setEmail("user@example.test");
        when(authentication.getName()).thenReturn(user.getEmail());
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        doReturn(Map.of("respuesta", "ok")).when(aiService).chat(22, "Hola");

        assertEquals(HttpStatus.OK, controller.chat(authentication, Map.of("mensaje", " Hola ")).getStatusCode());
        verify(aiService).chat(22, "Hola");
    }
}

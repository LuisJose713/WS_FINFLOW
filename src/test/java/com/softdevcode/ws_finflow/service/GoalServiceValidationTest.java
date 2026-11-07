package com.softdevcode.ws_finflow.service;

import com.softdevcode.ws_finflow.dto.GoalRequest;
import com.softdevcode.ws_finflow.entity.Goal;
import com.softdevcode.ws_finflow.repository.GoalContributionRepository;
import com.softdevcode.ws_finflow.repository.GoalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoalServiceValidationTest {
    @Mock GoalRepository goals;
    @Mock GoalContributionRepository contributions;
    @InjectMocks GoalService service;

    private GoalRequest request() {
        GoalRequest data = new GoalRequest();
        data.setTitle("Fondo de emergencia");
        data.setTargetAmount(1500000d);
        data.setCurrentAmount(150000d);
        data.setDeadline(LocalDate.of(2027, 6, 1));
        return data;
    }

    @Test
    void createsValidGoalForAuthenticatedOwner() {
        when(goals.save(any(Goal.class))).thenAnswer(a -> a.getArgument(0));
        Goal created = service.create(10L, request());
        assertEquals(10L, created.getUserId());
        assertEquals("Fondo de emergencia", created.getTitle());
        assertEquals(1500000d, created.getTargetAmount());
        assertEquals(150000d, created.getCurrentAmount());
        assertEquals("active", created.getStatus());
    }

    @Test
    void rejectsGoalWithMissingDeadline() {
        GoalRequest data = request();
        data.setDeadline(null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(10L, data));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verifyNoInteractions(goals);
    }

    @Test
    void rejectsSavingsGreaterThanTarget() {
        GoalRequest data = request();
        data.setCurrentAmount(1600000d);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.create(10L, data)).getStatusCode());
        verifyNoInteractions(goals);
    }

    @Test
    void rejectsNonFiniteGoalAmount() {
        GoalRequest data = request();
        data.setTargetAmount(Double.POSITIVE_INFINITY);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.create(10L, data)).getStatusCode());
        verifyNoInteractions(goals);
    }

    @Test
    void updatePreservesCurrentSavingsWhenNotProvided() {
        Goal existing = new Goal();
        existing.setUserId(10L);
        existing.setTargetAmount(1500000d);
        existing.setCurrentAmount(320000d);
        existing.setTitle("Fondo");
        when(goals.findByIdAndUserId(4L, 10L)).thenReturn(Optional.of(existing));
        when(goals.save(any(Goal.class))).thenAnswer(a -> a.getArgument(0));
        GoalRequest patch = new GoalRequest();
        patch.setTitle("Fondo mejorado");
        patch.setTargetAmount(1600000d);
        Goal updated = service.update(10L, 4L, patch);
        assertEquals(320000d, updated.getCurrentAmount());
        assertEquals("Fondo mejorado", updated.getTitle());
    }

    @Test
    void updateCannotLowerGoalBelowSavings() {
        Goal existing = new Goal();
        existing.setUserId(10L);
        existing.setTargetAmount(1500000d);
        existing.setCurrentAmount(320000d);
        when(goals.findByIdAndUserId(4L, 10L)).thenReturn(Optional.of(existing));
        GoalRequest patch = new GoalRequest();
        patch.setTargetAmount(200000d);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.update(10L, 4L, patch)).getStatusCode());
        verify(goals, never()).save(any());
    }
}

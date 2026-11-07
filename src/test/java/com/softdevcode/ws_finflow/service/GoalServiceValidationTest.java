package com.softdevcode.ws_finflow.service;

import com.softdevcode.ws_finflow.dto.GoalRequest;
import com.softdevcode.ws_finflow.dto.ContributionRequest;
import com.softdevcode.ws_finflow.entity.GoalContribution;
import org.mockito.ArgumentCaptor;
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

    private Goal activeGoal() {
        Goal goal = new Goal();
        goal.setUserId(10L);
        goal.setStatus("active");
        goal.setTargetAmount(1000d);
        goal.setCurrentAmount(200d);
        return goal;
    }

    private ContributionRequest contribution(Double amount) {
        ContributionRequest request = new ContributionRequest();
        request.setAmount(amount);
        return request;
    }

    @Test
    void rejectsMissingNegativeZeroOrNonFiniteContributions() {
        for (Double invalid : new Double[] {null, 0d, -50d, Double.NaN, Double.POSITIVE_INFINITY}) {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.addContribution(10L, 4L, contribution(invalid)));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.addContribution(10L, 4L, null)).getStatusCode());
        verifyNoInteractions(goals, contributions);
    }

    @Test
    void rejectsOverfundingWithoutCreatingAContribution() {
        when(goals.findByIdAndUserIdForUpdate(4L, 10L)).thenReturn(Optional.of(activeGoal()));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.addContribution(10L, 4L, contribution(801d)));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(contributions);
        verify(goals, never()).save(any());
    }

    @Test
    void acceptsExactRemainingBalanceAndCompletesGoal() {
        when(goals.findByIdAndUserIdForUpdate(4L, 10L)).thenReturn(Optional.of(activeGoal()));
        when(goals.save(any(Goal.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Goal updated = service.addContribution(10L, 4L, contribution(800d));
        assertEquals(1000d, updated.getCurrentAmount());
        assertEquals("completed", updated.getStatus());
        ArgumentCaptor<GoalContribution> captor = ArgumentCaptor.forClass(GoalContribution.class);
        verify(contributions).save(captor.capture());
        assertEquals(800d, captor.getValue().getAmount());
        assertEquals(10L, captor.getValue().getUserId());
        assertEquals(4L, captor.getValue().getGoalId());
    }

    @Test
    void rejectsContributionToCancelledGoal() {
        Goal goal = activeGoal();
        goal.setStatus("cancelled");
        when(goals.findByIdAndUserIdForUpdate(4L, 10L)).thenReturn(Optional.of(goal));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.addContribution(10L, 4L, contribution(10d))).getStatusCode());
        verifyNoInteractions(contributions);
    }

    @Test
    void rejectsUnrecognizedGoalStatus() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.updateStatus(10L, 4L, "hacked")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.updateStatus(10L, 4L, null)).getStatusCode());
        verifyNoInteractions(goals);
    }

}

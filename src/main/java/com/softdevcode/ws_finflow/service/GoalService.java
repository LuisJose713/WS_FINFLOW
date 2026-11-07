package com.softdevcode.ws_finflow.service;

import com.softdevcode.ws_finflow.dto.ContributionRequest;
import com.softdevcode.ws_finflow.dto.GoalRequest;
import com.softdevcode.ws_finflow.entity.Goal;
import com.softdevcode.ws_finflow.entity.GoalContribution;
import com.softdevcode.ws_finflow.repository.GoalContributionRepository;
import com.softdevcode.ws_finflow.repository.GoalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GoalService {

    private final GoalRepository             repository;
    private final GoalContributionRepository contributionRepository;

    public List<Goal> getAll(Long userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public Goal getById(Long userId, Long id) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meta no encontrada"));
    }

    @Transactional
    public Goal create(Long userId, GoalRequest request) {
        validateRequest(request, true);
        double currentAmount = request.getCurrentAmount() != null ? request.getCurrentAmount() : 0.0;
        if (currentAmount > request.getTargetAmount()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El ahorro inicial no puede superar la meta");
        }
        Goal goal = new Goal();
        goal.setUserId(userId);
        goal.setTitle(request.getTitle().trim());
        goal.setTargetAmount(request.getTargetAmount());
        goal.setCurrentAmount(request.getCurrentAmount() != null ? request.getCurrentAmount() : 0.0);
        goal.setDeadline(request.getDeadline());
        goal.setDescription(request.getDescription());
        goal.setStatus("active");
        goal.setCreatedAt(LocalDateTime.now());
        return repository.save(goal);
    }

    @Transactional
    public Goal update(Long userId, Long id, GoalRequest request) {
        validateRequest(request, false);
        Goal goal = getById(userId, id);
        double newTarget = request.getTargetAmount() == null ? goal.getTargetAmount() : request.getTargetAmount();
        double newCurrent = request.getCurrentAmount() == null ? goal.getCurrentAmount() : request.getCurrentAmount();
        if (!Double.isFinite(newTarget) || newTarget <= 0 ||
                !Double.isFinite(newCurrent) || newCurrent < 0 || newCurrent > newTarget) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El monto ahorrado no puede superar el objetivo");
        }
        if (request.getTitle()         != null) goal.setTitle(request.getTitle().trim());
        if (request.getTargetAmount()  != null) goal.setTargetAmount(request.getTargetAmount());
        if (request.getCurrentAmount() != null) goal.setCurrentAmount(request.getCurrentAmount());
        if (request.getDeadline()      != null) goal.setDeadline(request.getDeadline());
        if (request.getDescription()   != null) goal.setDescription(request.getDescription());
        if (request.getStatus()        != null) goal.setStatus(request.getStatus());
        return repository.save(goal);
    }

    private void validateRequest(GoalRequest request, boolean creating) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datos de la meta requeridos");
        }
        if (creating && (request.getTitle() == null || request.getTargetAmount() == null || request.getDeadline() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Completa título, objetivo y fecha límite");
        }
        if (request.getTitle() != null) {
            int length = request.getTitle().trim().length();
            if (length < 2 || length > 150) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El título debe tener entre 2 y 150 caracteres");
            }
        }
        if (request.getTargetAmount() != null) {
            double amount = request.getTargetAmount();
            if (!Double.isFinite(amount) || amount <= 0 || amount > 999_999_999_999.99) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Monto objetivo inválido");
            }
        }
        if (request.getCurrentAmount() != null) {
            double amount = request.getCurrentAmount();
            if (!Double.isFinite(amount) || amount < 0 || amount > 999_999_999_999.99) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ahorro inicial inválido");
            }
        }
        if (request.getDescription() != null && request.getDescription().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La descripción no puede superar 255 caracteres");
        }
        if (request.getStatus() != null &&
                !List.of("active", "completed", "cancelled").contains(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado de la meta inválido");
        }
    }

    @Transactional
    public Goal addContribution(Long userId, Long id, ContributionRequest request) {
        if (request == null || request.getAmount() == null
                || !Double.isFinite(request.getAmount()) || request.getAmount() <= 0
                || request.getAmount() > 999_999_999_999.99) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El aporte debe ser un monto positivo válido");
        }
        if (request.getNote() != null && request.getNote().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La nota no puede superar 255 caracteres");
        }
        Goal goal = repository.findByIdAndUserIdForUpdate(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meta no encontrada"));
        if (!"active".equals(goal.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Solo puedes aportar a metas activas");
        }
        if (goal.getCurrentAmount() == null || goal.getTargetAmount() == null
                || !Double.isFinite(goal.getCurrentAmount()) || !Double.isFinite(goal.getTargetAmount())
                || goal.getCurrentAmount() < 0 || goal.getTargetAmount() <= 0
                || goal.getCurrentAmount() >= goal.getTargetAmount()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El saldo de la meta no permite nuevos aportes");
        }
        double remaining = goal.getTargetAmount() - goal.getCurrentAmount();
        if (request.getAmount() > remaining) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El aporte supera el valor que falta para completar la meta");
        }

        double newAmount = goal.getCurrentAmount() + request.getAmount();
        goal.setCurrentAmount(newAmount);
        if (newAmount >= goal.getTargetAmount()) {
            goal.setStatus("completed");
        }

        GoalContribution contribution = new GoalContribution();
        contribution.setGoalId(id);
        contribution.setUserId(userId);
        contribution.setAmount(request.getAmount());
        contribution.setNote(request.getNote());
        contribution.setCreatedAt(LocalDateTime.now());
        contributionRepository.save(contribution);

        return repository.save(goal);
    }

    public List<GoalContribution> getContributions(Long userId, Long goalId) {
        getById(userId, goalId);
        return contributionRepository.findByGoalIdOrderByCreatedAtDesc(goalId);
    }

    @Transactional
    public Goal updateStatus(Long userId, Long id, String status) {
        if (status == null || !List.of("active", "completed", "cancelled").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado de la meta inválido");
        }
        Goal goal = getById(userId, id);
        goal.setStatus(status);
        return repository.save(goal);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        Goal goal = getById(userId, id);
        repository.delete(goal);
    }
}
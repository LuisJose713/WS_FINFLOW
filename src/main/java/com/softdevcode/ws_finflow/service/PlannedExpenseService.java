package com.softdevcode.ws_finflow.service;

import com.softdevcode.ws_finflow.dto.PlannedExpenseRequest;
import com.softdevcode.ws_finflow.dto.TransactionRequest;
import com.softdevcode.ws_finflow.entity.PlannedExpense;
import com.softdevcode.ws_finflow.entity.Transaction;
import com.softdevcode.ws_finflow.repository.PlannedExpenseRepository;
import com.softdevcode.ws_finflow.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PlannedExpenseService {

    private final PlannedExpenseRepository repository;
    private final TransactionRepository    transactionRepository;

    // ── Obtener todos ─────────────────────────────────────
    public List<PlannedExpense> getAll(Integer userId) {
        return repository.findByUserIdOrderByDueDateAsc(userId);
    }

    // ── Crear ─────────────────────────────────────────────
    @Transactional
    public PlannedExpense create(Integer userId, PlannedExpenseRequest req) {
        PlannedExpense pe = new PlannedExpense();
        pe.setUserId(userId);
        pe.setTitle(req.getTitle());
        validateAmount(req.getAmount());
        pe.setAmount(req.getAmount());
        pe.setCategoryId(req.getCategoryId());
        pe.setDueDate(req.getDueDate());
        pe.setNotes(req.getNotes());
        pe.setRemindAt(req.getRemindAt());
        pe.setStatus("pending");
        pe.setCreatedAt(LocalDateTime.now());
        return repository.save(pe);
    }

    // ── Editar ────────────────────────────────────────────
    @Transactional
    public PlannedExpense update(Integer userId, Long id, PlannedExpenseRequest req) {
        PlannedExpense pe = getById(userId, id);
        if (req.getTitle()      != null) pe.setTitle(req.getTitle());
        if (!"pending".equals(pe.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solo se puede editar un gasto pendiente");
        }
        if (req.getAmount() != null) {
            validateAmount(req.getAmount());
            pe.setAmount(req.getAmount());
        }
        if (req.getCategoryId() != null) pe.setCategoryId(req.getCategoryId());
        if (req.getDueDate()    != null) pe.setDueDate(req.getDueDate());
        if (req.getNotes()      != null) pe.setNotes(req.getNotes());
        if (req.getRemindAt()   != null) pe.setRemindAt(req.getRemindAt());
        return repository.save(pe);
    }

    // ── Ejecutar: marca como done y crea transacción real ─
    @Transactional
    public PlannedExpense execute(Integer userId, Long id) {
        PlannedExpense pe = repository.lockByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gasto planeado no encontrado"));

        if (!"pending".equals(pe.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El gasto ya fue ejecutado o cancelado");
        }

        // Crea la transacción real
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setAmount(pe.getAmount().doubleValue());
        tx.setType("expense");
        tx.setCategoryId(pe.getCategoryId());
        tx.setDescription(pe.getTitle());
        tx.setDate(LocalDateTime.now());
        tx.setCurrency("COP");
        tx.setStatus("completed");
        tx.setCreatedAt(LocalDateTime.now());
        Transaction saved = transactionRepository.save(tx);

        // Actualiza el gasto planeado
        pe.setStatus("done");
        pe.setExecutedTransactionId(saved.getId());
        return repository.save(pe);
    }

    // ── Cancelar ──────────────────────────────────────────
    @Transactional
    public PlannedExpense cancel(Integer userId, Long id) {
        PlannedExpense pe = getById(userId, id);
        if (!"pending".equals(pe.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solo se puede cancelar un gasto pendiente");
        }
        pe.setStatus("cancelled");
        return repository.save(pe);
    }

    // ── Eliminar ──────────────────────────────────────────
    @Transactional
    public void delete(Integer userId, Long id) {
        PlannedExpense pe = getById(userId, id);
        repository.delete(pe);
    }

    // ── Alertas: gastos vencidos pendientes ───────────────
    public List<PlannedExpense> getOverdue(Integer userId) {
        return repository.findByUserIdAndStatusAndDueDateLessThanEqual(
                userId, "pending", LocalDate.now());
    }

    // ── Recordatorios de hoy ──────────────────────────────
    public List<PlannedExpense> getTodayReminders(Integer userId) {
        return repository.findByUserIdAndRemindAt(userId, LocalDate.now());
    }

    private void validateAmount(java.math.BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El monto debe ser positivo");
        }
    }

    private PlannedExpense getById(Integer userId, Long id) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gasto planeado no encontrado"));
    }
}
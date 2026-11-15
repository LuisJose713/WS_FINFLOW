package com.softdevcode.ws_finflow.entity;

import jakarta.persistence.*;
import com.softdevcode.ws_finflow.security.EncryptedFields;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "planned_expenses")
public class PlannedExpense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(nullable = false)
    @Convert(converter = EncryptedFields.PlannedTitle.class)
    private String title;

    @Column(nullable = false)
    @Convert(converter = EncryptedFields.PlannedAmount.class)
    private BigDecimal amount;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "due_date")
    @Convert(converter = EncryptedFields.PlannedDue.class)
    private LocalDate dueDate;

    @Column
    @Convert(converter = EncryptedFields.PlannedNotes.class)
    private String notes;

    @Column(nullable = false)
    private String status = "pending"; // pending | done | cancelled

    @Column(name = "executed_transaction_id")
    private Long executedTransactionId;

    @Column(name = "remind_at")
    @Convert(converter = EncryptedFields.PlannedReminder.class)
    private LocalDate remindAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // ── Getters y setters ──────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getExecutedTransactionId() { return executedTransactionId; }
    public void setExecutedTransactionId(Long executedTransactionId) { this.executedTransactionId = executedTransactionId; }

    public LocalDate getRemindAt() { return remindAt; }
    public void setRemindAt(LocalDate remindAt) { this.remindAt = remindAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
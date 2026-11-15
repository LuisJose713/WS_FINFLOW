package com.softdevcode.ws_finflow.entity;

import jakarta.persistence.*;
import com.softdevcode.ws_finflow.security.EncryptedFields;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "goals")
public class Goal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "title")
    @Convert(converter = EncryptedFields.GoalTitle.class)
    private String title;

    @Column(name = "target_amount")
    @Convert(converter = EncryptedFields.GoalTarget.class)
    private Double targetAmount;

    @Column(name = "current_amount")
    @Convert(converter = EncryptedFields.GoalCurrent.class)
    private Double currentAmount = 0.0;

    @Column(name = "deadline")
    @Convert(converter = EncryptedFields.GoalDeadline.class)
    private LocalDate deadline;

    @Column(name = "status")
    private String status = "active"; // active | completed | cancelled

    @Column(name = "description")
    @Convert(converter = EncryptedFields.GoalDescription.class)
    private String description;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // ── Getters y setters ──────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Double getTargetAmount() { return targetAmount; }
    public void setTargetAmount(Double targetAmount) { this.targetAmount = targetAmount; }

    public Double getCurrentAmount() { return currentAmount; }
    public void setCurrentAmount(Double currentAmount) { this.currentAmount = currentAmount; }

    public LocalDate getDeadline() { return deadline; }
    public void setDeadline(LocalDate deadline) { this.deadline = deadline; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
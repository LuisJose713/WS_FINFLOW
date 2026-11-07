    package com.softdevcode.ws_finflow.entity;

    import jakarta.persistence.*;
    import java.time.LocalDateTime;


    @Entity
    @Table(name = "transactions")
    public class Transaction {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "user_id", nullable = false)
        private Integer userId;

        @Column(nullable = false)
        private Double amount;

        @Column(name = "type", nullable = false)
        private String type; // "income" o "expense"

        @Column(name = "category_id")
        private Long categoryId;

        @Column(name = "account_id")
        private Long accountId;

        private String description;

        @Column(name = "date")
        private LocalDateTime date;

        @Column(length = 3)
        private String currency;

        @Column(name = "status")
        private String status;

        @Column(name = "created_at")
        private LocalDateTime createdAt;

        // Getters y setters
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public Integer getUserId() { return userId; }
        public void setUserId(Integer userId) { this.userId = userId; }

        public Double getAmount() { return amount; }
        public void setAmount(Double amount) { this.amount = amount; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public Long getAccountId() { return accountId; }
        public void setAccountId(Long accountId) { this.accountId = accountId; }
        public Long getCategoryId() { return categoryId; }
        public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public LocalDateTime getDate() { return date; }
        public void setDate(LocalDateTime date) { this.date = date; }

        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }
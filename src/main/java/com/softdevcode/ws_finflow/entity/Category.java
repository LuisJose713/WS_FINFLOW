package com.softdevcode.ws_finflow.entity;

import jakarta.persistence.*;
import com.softdevcode.ws_finflow.security.EncryptedFields;
import java.time.LocalDateTime;

@Entity
@EntityListeners(com.softdevcode.ws_finflow.security.ProtectedLookupListener.class)
@Table(name = "categories")
public class Category {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @Convert(converter = EncryptedFields.CategoryName.class)
    private String name;
    @Column(name = "name_lookup")
    private String nameLookup;
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getNameLookup() { return nameLookup; }
    public void setNameLookup(String value) { nameLookup = value; }

    @Column(nullable = false)
    private String type; // "Income" o "Expense"

    // null = categoría de sistema, visible para todos pero solo lectura.
    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    @PrePersist public void beforeInsert() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}

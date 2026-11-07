package com.softdevcode.ws_finflow.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {
    @Setter
    @Getter
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Setter
    @Getter
    private String email;
    @Setter
    @Getter
    private String password;
    @Setter
    @Getter
    private String username;
    @Setter
    @Getter
    @Column(name = "created_at")
    private LocalDateTime create_At;
    @Getter @Setter
    @Column(name = "token_version", nullable = false)
    private Integer tokenVersion = 0;
    @Getter @Setter
    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    @Getter
    @Setter
    @Column(name = "role", nullable = false, length = 16)
    private String role = "USER";

    @PrePersist
    public void prePersist() {
        this.create_At = LocalDateTime.now();
    }


}
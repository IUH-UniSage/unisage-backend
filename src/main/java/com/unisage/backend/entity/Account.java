package com.unisage.backend.entity;

import com.unisage.backend.entity.enums.UserStatus;
import com.fasterxml.jackson.annotation.JsonManagedReference;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
 
@Entity
@Table(name = "accounts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class Account extends BaseEntity {
 
    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;
 
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
 
    @Column(unique = true, nullable = false)
    private String email;

    @Column(unique = true)
    private String code;
 
    @Column(name = "last_login")
    private LocalDateTime lastLogin;
 
    @OneToMany(mappedBy = "account", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonManagedReference
    private List<User> users;
}
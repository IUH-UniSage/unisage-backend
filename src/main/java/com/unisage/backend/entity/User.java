package com.unisage.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.unisage.backend.entity.enums.UserStatus;
import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
 
@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class User extends BaseEntity {
 
    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    @Transient
    @JsonIgnore
    @Override
    public Boolean getIsActive() {
        return status == UserStatus.ACTIVE;
    }

    @Override
    public void setIsActive(Boolean isActive) {
        // Do nothing, we use status
    }
 
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    @JsonBackReference
    private Account account;
 
    @Column(name = "first_name")
    private String firstName;
 
    @Column(name = "last_name")
    private String lastName;
 
    @Column(name = "avatar_url")
    private String avatarUrl;
 

 
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonBackReference
    private Role role;
 

 
    @Column(name = "personal_email")
    private String personalEmail;

    private String phone;
 
    private String gender;
 
    private String address;

    private String department;
}

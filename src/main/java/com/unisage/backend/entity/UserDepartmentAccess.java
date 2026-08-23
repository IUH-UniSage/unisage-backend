package com.unisage.backend.entity;

import java.io.Serializable;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonBackReference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_department_access")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@IdClass(UserDepartmentAccess.UserDepartmentAccessId.class)
public class UserDepartmentAccess {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @JsonBackReference
    private User user;

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    @JsonBackReference
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "access_level_id")
    private AccessLevel accessLevel;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserDepartmentAccessId implements Serializable {
        private UUID user;
        private UUID department;
    }
}

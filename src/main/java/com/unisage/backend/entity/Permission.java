package com.unisage.backend.entity;

import com.unisage.backend.entity.enums.PermissionMethod;
import com.unisage.backend.entity.enums.ResourceType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.UUID;

@Entity
@Table(
    name = "permissions",
    indexes = {
        @Index(name = "idx_permission_path_method", columnList = "path, method"),
        @Index(name = "idx_permission_name",        columnList = "name")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class Permission extends BaseEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** Human-readable key, e.g. USER_READ, DOCUMENT_INGEST */
    @Column(nullable = false, length = 100)
    private String name;

    /** Ant-style path pattern, e.g. /api/users/**, /api/documents/{id} */
    @Column(nullable = false, length = 255)
    private String path;

    /** HTTP method (GET / POST / PUT / PATCH / DELETE / ALL) */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PermissionMethod method;

    /** Which domain/resource this permission controls */
    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 50)
    private ResourceType resourceType;

    @Column(length = 255)
    private String description;

    @OneToMany(mappedBy = "permission", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<RolePermission> rolePermissions;

    @Builder.Default
    @Column(name = "access_level")
    private Integer accessLevel = null;    
}

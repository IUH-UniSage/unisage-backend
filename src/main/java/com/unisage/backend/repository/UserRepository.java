package com.unisage.backend.repository;
import com.unisage.backend.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {
    Optional<User> findByPhone(String phone);
    Optional<User> findByEmail(String email);
    Optional<User> findByCode(String code);

    @Override
    @EntityGraph(attributePaths = {"createdBy", "updatedBy"})
    Page<User> findAll(Pageable pageable);

    /** List page: also fetches role and accessLevel, which every UserResponse row reads. */
    @Override
    @EntityGraph(attributePaths = {"createdBy", "updatedBy", "role", "accessLevel"})
    Page<User> findAll(Specification<User> spec, Pageable pageable);

    /**
     * Loads the user together with its Role, RolePermissions and Permission
     * in a single query — avoids LazyInitializationException when called
     * outside a Hibernate session (e.g. DynamicAuthorizationManager).
     */
    @Query("""
        SELECT DISTINCT u FROM User u
        JOIN FETCH u.role r
        JOIN FETCH r.rolePermissions rp
        JOIN FETCH rp.permission
        WHERE u.id = :id
        """)
    Optional<User> findByIdWithPermissions(@Param("id") UUID id);
}

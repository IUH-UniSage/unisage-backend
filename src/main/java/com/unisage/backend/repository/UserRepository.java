package com.unisage.backend.repository;
import com.unisage.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByPhone(String phone);
    Optional<User> findByEmail(String email);
    Optional<User> findByCode(String code);

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
    @Query("""
            SELECT MAX(p.accessLevel)
            FROM User u
            JOIN u.role r
            JOIN r.rolePermissions rp
            JOIN rp.permission p
            WHERE u.id = :userId
        """)
    Integer findMaxAccessLevelByUserId(@Param("userId") UUID userId);
}

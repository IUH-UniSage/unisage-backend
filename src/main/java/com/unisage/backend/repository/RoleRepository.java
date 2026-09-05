package com.unisage.backend.repository;
import com.unisage.backend.entity.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID> {
 Optional<Role> findByName(String name);

 @Override
 @EntityGraph(attributePaths = {"createdBy", "updatedBy"})
 Page<Role> findAll(Pageable pageable);
}

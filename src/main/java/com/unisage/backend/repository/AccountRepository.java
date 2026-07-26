package com.unisage.backend.repository;

import com.unisage.backend.entity.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    @Query("SELECT a FROM Account a WHERE a.id = :id AND a.isActive = true")
    Optional<Account> findActiveById(UUID id);

    @Query("SELECT a FROM Account a WHERE a.isActive = true")
    List<Account> findAllActive();

    Optional<Account> findByEmail(String email);
    Optional<Account> findByCode(String code);
}

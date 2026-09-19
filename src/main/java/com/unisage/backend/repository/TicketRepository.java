package com.unisage.backend.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import com.unisage.backend.entity.Ticket;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, UUID>, JpaSpecificationExecutor<Ticket> {

    boolean existsByMessageId(UUID messageId);

    Optional<Ticket> findByMessageId(UUID messageId);

    List<Ticket> findByMessageIdIn(Collection<UUID> messageIds);

    Optional<Ticket> findByIdAndUserId(UUID id, UUID userId);

    @Override
    @EntityGraph(attributePaths = {"user"})
    Page<Ticket> findAll(Specification<Ticket> spec, Pageable pageable);
}

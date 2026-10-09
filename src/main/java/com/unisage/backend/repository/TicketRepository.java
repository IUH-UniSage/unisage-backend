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
import com.unisage.backend.entity.enums.TicketStatus;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, UUID>, JpaSpecificationExecutor<Ticket> {

    /** UNISAGE-72: dashboard's "open support requests" tile. */
    long countByStatus(TicketStatus status);

    /** The regular Report of a message (at most one); calculation-item tickets are excluded. */
    boolean existsByMessageIdAndCalculationItemIdIsNull(UUID messageId);

    Optional<Ticket> findByMessageIdAndCalculationItemIdIsNull(UUID messageId);

    List<Ticket> findByMessageIdInAndCalculationItemIdIsNull(Collection<UUID> messageIds);

    /** The {@code AI_CALCULATION_WRONG} ticket of one calculation item (at most one). */
    Optional<Ticket> findByMessageIdAndCalculationItemId(UUID messageId, String calculationItemId);

    Optional<Ticket> findByIdAndUserId(UUID id, UUID userId);

    @Override
    @EntityGraph(attributePaths = {"user"})
    Page<Ticket> findAll(Specification<Ticket> spec, Pageable pageable);
}

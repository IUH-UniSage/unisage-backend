package com.unisage.backend.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.unisage.backend.entity.AuditLog;
import com.unisage.backend.entity.User;
import com.unisage.backend.repository.AuditLogRepository;
import com.unisage.backend.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Persists {@link AuditLog} rows produced by {@link AuditEventListener}, always in its own,
 * brand-new transaction (REQUIRES_NEW). This is deliberate: the listener fires from inside the
 * flush of the transaction that made the audited change, and that write is committed
 * asynchronously to it (see {@link AuditEventListener} for the afterCommit hand-off) — an audit
 * row must never be able to roll back the business transaction it describes, nor vice versa.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogWriter {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(AuditLog entry) {
        try {
            if (entry.getActorId() != null && entry.getActorName() == null) {
                userRepository.findById(entry.getActorId())
                        .map(User::getFullName)
                        .ifPresent(entry::setActorName);
            }
            auditLogRepository.save(entry);
        } catch (Exception e) {
            // Audit-log persistence must never break or roll back the business operation it is
            // describing — that operation has already committed by the time this runs.
            log.warn("Failed to persist audit log entry for {} {} (resourceId={}): {}",
                    entry.getAction(), entry.getResourceType(), entry.getResourceId(), e.getMessage(), e);
        }
    }
}

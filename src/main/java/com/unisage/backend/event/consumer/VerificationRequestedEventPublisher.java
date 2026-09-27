package com.unisage.backend.event.consumer;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.unisage.backend.event.VerificationRequestedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes to Redis only after the transaction that created/replaced a {@code QUEUED} job
 * commits — separate channel from {@link ModelRegistryEventPublisher}, which signals a change to
 * the routing snapshot itself. This one just wakes the Python verifier up early; it never carries
 * job data (the claim endpoint is the only source of truth for what to verify).
 */
@org.springframework.stereotype.Component
@RequiredArgsConstructor
@Slf4j
public class VerificationRequestedEventPublisher {

    public static final String CHANNEL = "model-registry:verification-requested";

    private final StringRedisTemplate redisTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerificationRequested(VerificationRequestedEvent event) {
        try {
            redisTemplate.convertAndSend(CHANNEL, String.valueOf(event.jobId()));
        } catch (Exception e) {
            // Best-effort wake-up only — Python's Beat task also polls every 15s.
            log.warn("Failed to publish verification-requested for job {} to Redis", event.jobId(), e);
        }
    }
}

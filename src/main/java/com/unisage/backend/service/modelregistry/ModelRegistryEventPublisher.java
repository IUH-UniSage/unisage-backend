package com.unisage.backend.service.modelregistry;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Publishes to Redis only after the transaction that bumped the version commits. */
@org.springframework.stereotype.Component
@RequiredArgsConstructor
@Slf4j
public class ModelRegistryEventPublisher {

    public static final String CHANNEL = "model-registry:updates";

    private final StringRedisTemplate redisTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onModelRegistryChanged(ModelRegistryChangedEvent event) {
        try {
            redisTemplate.convertAndSend(CHANNEL, String.valueOf(event.version()));
        } catch (Exception e) {
            // Best-effort signal only — every worker also polls the version, so a missed
            // publish is recovered from, never a correctness issue.
            log.warn("Failed to publish model registry version {} to Redis", event.version(), e);
        }
    }
}

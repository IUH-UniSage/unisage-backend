package com.unisage.backend.scheduler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.unisage.backend.service.guestsession.GuestSessionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Purges expired {@code GuestSession}s and the still-unclaimed data attached to them, since
 * Postgres has no Redis-style automatic TTL eviction. Runs daily, processing expired sessions in
 * bounded batches (via {@link GuestSessionService#purgeExpiredBatch}) rather than loading every
 * expired row into memory at once. See {@code docs/adr/0004-guest-session-ttl-and-cleanup.md} for
 * the policy this implements.
 *
 * <p>The actual per-batch delete (messages, then usage limits, then conversations, then the guest
 * sessions themselves — no {@code ON DELETE CASCADE} exists anywhere in this schema) lives in
 * {@code GuestSessionServiceImpl}, not here: {@code @Transactional} only applies through Spring's
 * proxy, so a self-invoked loop inside this same class would silently run with no transaction.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GuestSessionCleanupJob {

    private final GuestSessionService guestSessionService;

    @Value("${app.guest-session.cleanup.batch-size:500}")
    private int batchSize;

    @Scheduled(cron = "${app.guest-session.cleanup.cron:0 0 3 * * *}")
    public void purgeExpiredSessions() {
        int totalPurged = 0;
        int purgedThisBatch;
        do {
            purgedThisBatch = guestSessionService.purgeExpiredBatch(batchSize);
            totalPurged += purgedThisBatch;
        } while (purgedThisBatch == batchSize);

        if (totalPurged > 0) {
            log.info("Purged {} expired guest session(s)", totalPurged);
        }
    }
}

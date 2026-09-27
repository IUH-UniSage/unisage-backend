package com.unisage.backend.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.unisage.backend.service.guestsession.GuestSessionService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuestSessionCleanupJobTest {

    private static final int B = GuestSessionCleanupJob.BATCH_SIZE;

    private GuestSessionService guestSessionService;
    private GuestSessionCleanupJob job;

    @BeforeEach
    void setUp() {
        guestSessionService = mock(GuestSessionService.class);
        job = new GuestSessionCleanupJob(guestSessionService);
    }

    @Test
    void purgeExpiredSessions_singleEmptyBatch_stopsAfterOneCall() {
        when(guestSessionService.purgeExpiredBatch(B)).thenReturn(0);

        job.purgeExpiredSessions();

        verify(guestSessionService, times(1)).purgeExpiredBatch(B);
    }

    @Test
    void purgeExpiredSessions_multipleFullBatches_loopsUntilAPartialBatch() {
        // First two calls return a full batch (loop continues), third returns a partial batch
        // (loop stops) - proves the multi-batch loop actually repeats instead of
        // only ever handling one page.
        when(guestSessionService.purgeExpiredBatch(B)).thenReturn(B, B, 1);

        job.purgeExpiredSessions();

        verify(guestSessionService, times(3)).purgeExpiredBatch(B);
    }
}

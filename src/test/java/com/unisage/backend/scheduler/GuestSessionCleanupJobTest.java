package com.unisage.backend.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.unisage.backend.service.guestsession.GuestSessionService;
import com.unisage.backend.service.systemconfig.SystemConfigResolver;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuestSessionCleanupJobTest {

    private GuestSessionService guestSessionService;
    private GuestSessionCleanupJob job;

    @BeforeEach
    void setUp() {
        guestSessionService = mock(GuestSessionService.class);
        SystemConfigResolver configResolver = mock(SystemConfigResolver.class);
        when(configResolver.getInt(any(), anyInt())).thenReturn(2);
        job = new GuestSessionCleanupJob(guestSessionService, configResolver);
    }

    @Test
    void purgeExpiredSessions_singleEmptyBatch_stopsAfterOneCall() {
        when(guestSessionService.purgeExpiredBatch(2)).thenReturn(0);

        job.purgeExpiredSessions();

        verify(guestSessionService, times(1)).purgeExpiredBatch(2);
    }

    @Test
    void purgeExpiredSessions_multipleFullBatches_loopsUntilAPartialBatch() {
        // batchSize=2: first two calls return a full batch (loop continues), third returns a
        // partial batch (loop stops) - proves the multi-batch loop actually repeats instead of
        // only ever handling one page.
        when(guestSessionService.purgeExpiredBatch(2)).thenReturn(2, 2, 1);

        job.purgeExpiredSessions();

        verify(guestSessionService, times(3)).purgeExpiredBatch(2);
    }
}

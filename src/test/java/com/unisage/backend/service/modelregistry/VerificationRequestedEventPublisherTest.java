package com.unisage.backend.service.modelregistry;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class VerificationRequestedEventPublisherTest {

    @Test
    void onVerificationRequested_publishesJobIdToChannel() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        VerificationRequestedEventPublisher publisher = new VerificationRequestedEventPublisher(redisTemplate);
        UUID jobId = UUID.randomUUID();

        publisher.onVerificationRequested(new VerificationRequestedEvent(jobId));

        verify(redisTemplate).convertAndSend(VerificationRequestedEventPublisher.CHANNEL, jobId.toString());
    }

    @Test
    void redisFailure_isSwallowed_neverThrows() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        doThrow(new RuntimeException("redis down")).when(redisTemplate).convertAndSend(anyString(), any());
        VerificationRequestedEventPublisher publisher = new VerificationRequestedEventPublisher(redisTemplate);

        assertThatCode(() -> publisher.onVerificationRequested(new VerificationRequestedEvent(UUID.randomUUID())))
                .doesNotThrowAnyException();
    }
}

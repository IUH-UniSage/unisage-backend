package com.unisage.backend.service.modelregistry;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ModelRegistryEventPublisherTest {

    @Test
    void onModelRegistryChanged_publishesVersionToChannel() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ModelRegistryEventPublisher publisher = new ModelRegistryEventPublisher(redisTemplate);

        publisher.onModelRegistryChanged(new ModelRegistryChangedEvent(5L));

        verify(redisTemplate).convertAndSend(ModelRegistryEventPublisher.CHANNEL, "5");
    }

    @Test
    void redisFailure_isSwallowed_neverThrows() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        doThrow(new RuntimeException("redis down")).when(redisTemplate).convertAndSend(anyString(), any());
        ModelRegistryEventPublisher publisher = new ModelRegistryEventPublisher(redisTemplate);

        assertThatCode(() -> publisher.onModelRegistryChanged(new ModelRegistryChangedEvent(1L)))
                .doesNotThrowAnyException();
    }
}

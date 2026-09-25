package com.unisage.backend.service.modelregistry;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** No Redis, no DB — JdbcTemplate and the event publisher are both mocked. */
class ModelRegistryVersionServiceUnitTest {

    @Test
    void bump_returnsNewVersion_andPublishesEvent() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class))).thenReturn(42L);

        ModelRegistryVersionService service = new ModelRegistryVersionService(jdbcTemplate, eventPublisher);
        long result = service.bump();

        assertThat(result).isEqualTo(42L);
        verify(eventPublisher).publishEvent(new ModelRegistryChangedEvent(42L));
    }

    @Test
    void currentVersion_readsWithoutMutating() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class))).thenReturn(7L);

        ModelRegistryVersionService service = new ModelRegistryVersionService(jdbcTemplate, eventPublisher);

        assertThat(service.currentVersion()).isEqualTo(7L);
    }
}

package com.unisage.backend.service.systemconfig;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisage.backend.dto.request.UpdateSystemConfigRequest;
import com.unisage.backend.dto.response.SystemConfigResponse;
import com.unisage.backend.entity.SystemConfig;
import com.unisage.backend.entity.enums.SystemConfigCategory;
import com.unisage.backend.entity.enums.ValueType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.SystemConfigRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemConfigServiceImplTest {

    private SystemConfigRepository systemConfigRepository;
    private SystemConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        systemConfigRepository = mock(SystemConfigRepository.class);
        service = new SystemConfigServiceImpl(systemConfigRepository, new ObjectMapper());
        when(systemConfigRepository.save(any(SystemConfig.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private SystemConfig config(String key, String value, ValueType type, boolean editable) {
        return SystemConfig.builder()
                .id(UUID.randomUUID())
                .configKey(key)
                .value(value)
                .valueType(type)
                .category(SystemConfigCategory.CHAT)
                .label("Label")
                .description("Description")
                .isEditable(editable)
                .isActive(true)
                .build();
    }

    @Test
    void getAll_noCategory_returnsEveryRow() {
        when(systemConfigRepository.findAll())
                .thenReturn(List.of(config("a", "1", ValueType.NUMBER, true)));

        List<SystemConfigResponse> result = service.getAll(null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).configKey()).isEqualTo("a");
    }

    @Test
    void getAll_withCategory_filtersByCategory() {
        when(systemConfigRepository.findAllByCategory(SystemConfigCategory.CHAT))
                .thenReturn(List.of(config("chat.key", "1", ValueType.NUMBER, true)));

        List<SystemConfigResponse> result = service.getAll(SystemConfigCategory.CHAT);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).category()).isEqualTo(SystemConfigCategory.CHAT);
    }

    @Test
    void getByKey_notFound_throwsAppException() {
        when(systemConfigRepository.findByConfigKey("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByKey("missing"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SYSTEM_CONFIG_NOT_FOUND);
    }

    @Test
    void updateValue_numberType_validValue_updates() {
        SystemConfig existing = config("chat.max_history_messages", "20", ValueType.NUMBER, true);
        when(systemConfigRepository.findByConfigKey("chat.max_history_messages"))
                .thenReturn(Optional.of(existing));

        SystemConfigResponse result = service.updateValue(
                "chat.max_history_messages", UpdateSystemConfigRequest.builder().value("40").build());

        assertThat(result.value()).isEqualTo("40");
    }

    @Test
    void updateValue_numberType_invalidValue_throwsInvalidValueError() {
        SystemConfig existing = config("chat.max_history_messages", "20", ValueType.NUMBER, true);
        when(systemConfigRepository.findByConfigKey("chat.max_history_messages"))
                .thenReturn(Optional.of(existing));

        UpdateSystemConfigRequest request = UpdateSystemConfigRequest.builder().value("not-a-number").build();

        assertThatThrownBy(() -> service.updateValue("chat.max_history_messages", request))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SYSTEM_CONFIG_INVALID_VALUE);
    }

    @Test
    void updateValue_booleanType_invalidValue_throwsInvalidValueError() {
        SystemConfig existing = config("chat.usage_limit.enabled", "false", ValueType.BOOLEAN, true);
        when(systemConfigRepository.findByConfigKey("chat.usage_limit.enabled"))
                .thenReturn(Optional.of(existing));

        UpdateSystemConfigRequest request = UpdateSystemConfigRequest.builder().value("yes").build();

        assertThatThrownBy(() -> service.updateValue("chat.usage_limit.enabled", request))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SYSTEM_CONFIG_INVALID_VALUE);
    }

    @Test
    void updateValue_jsonType_invalidValue_throwsInvalidValueError() {
        SystemConfig existing = config("ingest.allowed_file_extensions", "[\".txt\"]", ValueType.JSON, true);
        when(systemConfigRepository.findByConfigKey("ingest.allowed_file_extensions"))
                .thenReturn(Optional.of(existing));

        UpdateSystemConfigRequest request = UpdateSystemConfigRequest.builder().value("not-json").build();

        assertThatThrownBy(() -> service.updateValue("ingest.allowed_file_extensions", request))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SYSTEM_CONFIG_INVALID_VALUE);
    }

    @Test
    void updateValue_notEditable_throwsNotEditableError() {
        SystemConfig existing = config("locked.key", "1", ValueType.NUMBER, false);
        when(systemConfigRepository.findByConfigKey("locked.key")).thenReturn(Optional.of(existing));

        UpdateSystemConfigRequest request = UpdateSystemConfigRequest.builder().value("2").build();

        assertThatThrownBy(() -> service.updateValue("locked.key", request))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SYSTEM_CONFIG_NOT_EDITABLE);
    }
}

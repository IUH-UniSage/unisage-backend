package com.unisage.backend.service.systemconfig;

import java.util.List;

import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisage.backend.dto.request.UpdateSystemConfigRequest;
import com.unisage.backend.dto.response.SystemConfigResponse;
import com.unisage.backend.entity.SystemConfig;
import com.unisage.backend.entity.enums.SystemConfigCategory;
import com.unisage.backend.entity.enums.ValueType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.SystemConfigRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SystemConfigServiceImpl implements SystemConfigService {

    private final SystemConfigRepository systemConfigRepository;
    private final ObjectMapper objectMapper;
    private final MultipartProperties multipartProperties;

    static final String MAX_FILE_SIZE_KEY = "ingest.max_file_size_mb";

    @Override
    public List<SystemConfigResponse> getAll(SystemConfigCategory category) {
        List<SystemConfig> configs = category != null
                ? systemConfigRepository.findAllByCategory(category)
                : systemConfigRepository.findAll();
        return configs.stream().map(this::mapToResponse).toList();
    }

    @Override
    public SystemConfigResponse getByKey(String configKey) {
        return mapToResponse(findByKeyOrThrow(configKey));
    }

    @Override
    @Transactional
    public SystemConfigResponse updateValue(String configKey, UpdateSystemConfigRequest request) {
        SystemConfig config = findByKeyOrThrow(configKey);

        if (Boolean.FALSE.equals(config.getIsEditable())) {
            throw new AppException(ErrorCode.SYSTEM_CONFIG_NOT_EDITABLE);
        }

        validateValue(request.value(), config.getValueType());
        if (MAX_FILE_SIZE_KEY.equals(configKey)) {
            validateMaxFileSize(request.value());
        }
        config.setValue(request.value());

        config = systemConfigRepository.save(config);
        return mapToResponse(config);
    }

    private SystemConfig findByKeyOrThrow(String configKey) {
        return systemConfigRepository.findByConfigKey(configKey)
                .orElseThrow(() -> new AppException(ErrorCode.SYSTEM_CONFIG_NOT_FOUND));
    }

    private void validateValue(String value, ValueType valueType) {
        switch (valueType) {
            case NUMBER -> {
                try {
                    Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    throw new AppException(ErrorCode.SYSTEM_CONFIG_INVALID_VALUE);
                }
            }
            case BOOLEAN -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw new AppException(ErrorCode.SYSTEM_CONFIG_INVALID_VALUE);
                }
            }
            case JSON -> {
                try {
                    objectMapper.readTree(value);
                } catch (Exception e) {
                    throw new AppException(ErrorCode.SYSTEM_CONFIG_INVALID_VALUE);
                }
            }
            case STRING -> {
                // Any non-null string is valid; @NotBlank on the request already rejects blank.
            }
        }
    }

    /**
     * Spring rejects any multipart body above {@code spring.servlet.multipart.max-file-size} before
     * FileServiceImpl ever sees it, so a higher admin value would silently have no effect.
     */
    private void validateMaxFileSize(String value) {
        double mb = Double.parseDouble(value);
        long ceilingMb = multipartProperties.getMaxFileSize().toMegabytes();
        if (mb <= 0 || mb > ceilingMb) {
            throw new AppException(ErrorCode.SYSTEM_CONFIG_FILE_SIZE_OUT_OF_RANGE);
        }
    }

    private SystemConfigResponse mapToResponse(SystemConfig config) {
        return SystemConfigResponse.builder()
                .id(config.getId())
                .configKey(config.getConfigKey())
                .value(config.getValue())
                .valueType(config.getValueType())
                .category(config.getCategory())
                .label(config.getLabel())
                .description(config.getDescription())
                .isEditable(config.getIsEditable())
                .isActive(config.getIsActive())
                .createdAt(config.getCreatedAt())
                .createdBy(config.getCreatedBy() != null ? config.getCreatedBy().getId().toString() : null)
                .createdByName(config.getCreatedBy() != null ? config.getCreatedBy().getFullName() : null)
                .updatedAt(config.getUpdatedAt())
                .updatedBy(config.getUpdatedBy() != null ? config.getUpdatedBy().getId().toString() : null)
                .updatedByName(config.getUpdatedBy() != null ? config.getUpdatedBy().getFullName() : null)
                .build();
    }
}

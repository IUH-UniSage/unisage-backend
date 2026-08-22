package com.unisage.backend.service.accesslevel;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.unisage.backend.dto.request.AccessLevelRequest;
import com.unisage.backend.dto.response.AccessLevelResponse;
import com.unisage.backend.entity.AccessLevel;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.AccessLevelRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AccessLevelServiceImpl implements AccessLevelService {

    private final AccessLevelRepository accessLevelRepository;

    @Override
    @Transactional
    public AccessLevelResponse createAccessLevel(AccessLevelRequest request) {
        if (accessLevelRepository.existsByLevel(request.level())) {
            throw new AppException(ErrorCode.ACCESS_LEVEL_EXISTED);
        }

        AccessLevel accessLevel = AccessLevel.builder()
                .level(request.level())
                .description(request.description())
                .build();
        accessLevel = accessLevelRepository.save(accessLevel);
        return mapToResponse(accessLevel);
    }

    @Override
    @Transactional
    public AccessLevelResponse updateAccessLevel(UUID id, AccessLevelRequest request) {
        AccessLevel accessLevel = accessLevelRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ACCESS_LEVEL_NOT_FOUND));

        if (request.level() != null && !request.level().equals(accessLevel.getLevel())) {
            if (accessLevelRepository.existsByLevel(request.level())) {
                throw new AppException(ErrorCode.ACCESS_LEVEL_EXISTED);
            }
            accessLevel.setLevel(request.level());
        }
        accessLevel.setDescription(request.description());

        accessLevel = accessLevelRepository.save(accessLevel);
        return mapToResponse(accessLevel);
    }

    @Override
    public AccessLevelResponse getAccessLevelById(UUID id) {
        AccessLevel accessLevel = accessLevelRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ACCESS_LEVEL_NOT_FOUND));
        return mapToResponse(accessLevel);
    }

    @Override
    public List<AccessLevelResponse> getAllAccessLevels() {
        return accessLevelRepository.findAll().stream()
                .sorted(Comparator.comparing(AccessLevel::getLevel))
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional
    public void deleteAccessLevel(UUID id) {
        AccessLevel accessLevel = accessLevelRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ACCESS_LEVEL_NOT_FOUND));
        accessLevel.setIsActive(false);
        accessLevelRepository.save(accessLevel);
    }

    private AccessLevelResponse mapToResponse(AccessLevel accessLevel) {
        return AccessLevelResponse.builder()
                .id(accessLevel.getId())
                .level(accessLevel.getLevel())
                .description(accessLevel.getDescription())
                .isActive(accessLevel.getIsActive())
                .createdAt(accessLevel.getCreatedAt())
                .createdBy(accessLevel.getCreatedBy())
                .updatedAt(accessLevel.getUpdatedAt())
                .updatedBy(accessLevel.getUpdatedBy())
                .build();
    }
}

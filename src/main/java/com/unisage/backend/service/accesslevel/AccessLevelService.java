package com.unisage.backend.service.accesslevel;

import java.util.List;
import java.util.UUID;

import com.unisage.backend.dto.request.AccessLevelRequest;
import com.unisage.backend.dto.response.AccessLevelResponse;

public interface AccessLevelService {

    AccessLevelResponse createAccessLevel(AccessLevelRequest request);

    AccessLevelResponse updateAccessLevel(UUID id, AccessLevelRequest request);

    AccessLevelResponse getAccessLevelById(UUID id);

    List<AccessLevelResponse> getAllAccessLevels();

    void deleteAccessLevel(UUID id);
}

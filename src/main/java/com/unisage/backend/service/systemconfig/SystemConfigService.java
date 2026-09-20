package com.unisage.backend.service.systemconfig;

import java.util.List;

import com.unisage.backend.dto.request.UpdateSystemConfigRequest;
import com.unisage.backend.dto.response.SystemConfigResponse;
import com.unisage.backend.entity.enums.SystemConfigCategory;

public interface SystemConfigService {

    List<SystemConfigResponse> getAll(SystemConfigCategory category);

    SystemConfigResponse getByKey(String configKey);

    SystemConfigResponse updateValue(String configKey, UpdateSystemConfigRequest request);
}

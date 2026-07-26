package com.unisage.backend.dto.response;

import java.util.List;

public record EmbedResponse(
    String status,
    int totalVectorsUpserted,
    List<String> pointIds
) {}

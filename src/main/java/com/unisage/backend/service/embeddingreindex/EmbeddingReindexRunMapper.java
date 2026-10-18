package com.unisage.backend.service.embeddingreindex;

import com.unisage.backend.dto.response.EmbeddingReindexRunResponse;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.EmbeddingReindexRun;

final class EmbeddingReindexRunMapper {

    private EmbeddingReindexRunMapper() {}

    static EmbeddingReindexRunResponse toResponse(EmbeddingReindexRun run) {
        ChatModel model = run.getTargetChatModel();
        return EmbeddingReindexRunResponse.builder()
                .id(run.getId())
                .status(run.getStatus())
                .sourceCollection(run.getSourceCollection())
                .targetCollection(run.getTargetCollection())
                .targetModel(new EmbeddingReindexRunResponse.TargetModel(
                        model.getId(),
                        model.getDisplayName(),
                        model.getLlmProvider(),
                        model.getLlmModelName(),
                        model.getEmbeddingDimension()))
                .totalPoints(run.getTotalPoints())
                .processedPoints(run.getProcessedPoints())
                .failedPoints(run.getFailedPoints())
                .errorMessage(run.getErrorMessage())
                .createdAt(run.getCreatedAt())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .build();
    }
}

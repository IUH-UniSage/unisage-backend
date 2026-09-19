package com.unisage.backend.dto.request;

import com.unisage.backend.entity.enums.TicketStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record UpdateTicketRequest(
    @NotNull(message = "Trạng thái không được để trống")
    TicketStatus status,

    @Size(max = 2000, message = "Phản hồi không được vượt quá 2000 ký tự")
    String resolution
) {}

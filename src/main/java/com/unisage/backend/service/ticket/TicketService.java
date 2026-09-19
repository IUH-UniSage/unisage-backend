package com.unisage.backend.service.ticket;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;

import com.unisage.backend.dto.request.CreateTicketRequest;
import com.unisage.backend.dto.request.UpdateTicketRequest;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.dto.response.TicketDetailResponse;
import com.unisage.backend.dto.response.TicketResponse;
import com.unisage.backend.entity.enums.TicketStatus;
import com.unisage.backend.entity.enums.TicketType;

public interface TicketService {

    TicketResponse create(CreateTicketRequest request);

    PageResponse<List<TicketResponse>> getMyTickets(TicketStatus status, Pageable pageable);

    TicketDetailResponse getMyTicket(UUID id);

    PageResponse<List<TicketResponse>> getAll(String query, TicketStatus status, TicketType type, Pageable pageable);

    TicketDetailResponse getById(UUID id);

    TicketDetailResponse update(UUID id, UpdateTicketRequest request);
}

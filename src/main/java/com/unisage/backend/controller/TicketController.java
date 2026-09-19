package com.unisage.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.request.CreateTicketRequest;
import com.unisage.backend.dto.request.UpdateTicketRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.dto.response.TicketDetailResponse;
import com.unisage.backend.dto.response.TicketResponse;
import com.unisage.backend.entity.enums.TicketStatus;
import com.unisage.backend.entity.enums.TicketType;
import com.unisage.backend.service.ticket.TicketService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    // ── User (own tickets; reachable by any authenticated user) ──────────────────────────────────

    @PostMapping
    public ResponseEntity<ApiResponse<TicketResponse>> create(@Valid @RequestBody CreateTicketRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(ticketService.create(request)));
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<PageResponse<List<TicketResponse>>>> getMine(
            @RequestParam(required = false) TicketStatus status,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(ticketService.getMyTickets(status, pageable)));
    }

    @GetMapping("/my/{id}")
    public ResponseEntity<ApiResponse<TicketDetailResponse>> getMine(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(ticketService.getMyTicket(id)));
    }

    // ── Admin (TICKET_* permissions) ─────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<List<TicketResponse>>>> getAll(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) TicketStatus status,
            @RequestParam(required = false) TicketType type,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(ticketService.getAll(q, status, type, pageable)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TicketDetailResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(ticketService.getById(id)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<TicketDetailResponse>> update(
            @PathVariable UUID id, @Valid @RequestBody UpdateTicketRequest request) {
        return ResponseEntity.ok(ApiResponse.success(ticketService.update(id, request)));
    }
}

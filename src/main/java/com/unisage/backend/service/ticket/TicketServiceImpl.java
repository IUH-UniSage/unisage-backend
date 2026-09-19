package com.unisage.backend.service.ticket;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.unisage.backend.dto.request.CreateTicketRequest;
import com.unisage.backend.dto.request.UpdateTicketRequest;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.dto.response.TicketDetailResponse;
import com.unisage.backend.dto.response.TicketResponse;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.Ticket;
import com.unisage.backend.entity.User;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.TicketStatus;
import com.unisage.backend.entity.enums.TicketType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.repository.TicketRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.utils.SecurityUtil;

import jakarta.persistence.criteria.Predicate;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    private final TicketRepository ticketRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final SecurityUtil securityUtil;

    @Override
    @Transactional
    public TicketResponse create(CreateTicketRequest request) {
        UUID userId = securityUtil.getCurrentUserId();

        // Unknown message, someone else's conversation and a non-assistant message all get the same
        // error so a caller cannot probe which message ids exist.
        Message message = messageRepository.findById(request.messageId())
                .orElseThrow(() -> new AppException(ErrorCode.TICKET_MESSAGE_INVALID));
        boolean ownedByCaller = message.getConversation().getUser() != null
                && message.getConversation().getUser().getId().equals(userId);
        if (message.getRole() != MsgRole.ASSISTANT || !ownedByCaller) {
            throw new AppException(ErrorCode.TICKET_MESSAGE_INVALID);
        }
        if (ticketRepository.existsByMessageId(message.getId())) {
            throw new AppException(ErrorCode.TICKET_ALREADY_EXISTS);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        Ticket ticket = Ticket.builder()
                .user(user)
                .message(message)
                .type(request.type())
                .title(request.title().trim())
                .description(request.description().trim())
                .build();
        try {
            ticket = ticketRepository.saveAndFlush(ticket);
        } catch (DataIntegrityViolationException e) {
            // Two concurrent requests for the same message: the unique(message_id) constraint wins.
            throw new AppException(ErrorCode.TICKET_ALREADY_EXISTS);
        }
        return mapToResponse(ticket);
    }

    @Override
    @Transactional
    public PageResponse<List<TicketResponse>> getMyTickets(TicketStatus status, Pageable pageable) {
        UUID userId = securityUtil.getCurrentUserId();
        Page<Ticket> page = ticketRepository.findAll(buildSpec(userId, null, status, null), pageable);
        return PageResponse.fromPage(page, this::mapToResponse);
    }

    @Override
    @Transactional
    public TicketDetailResponse getMyTicket(UUID id) {
        Ticket ticket = ticketRepository.findByIdAndUserId(id, securityUtil.getCurrentUserId())
                .orElseThrow(() -> new AppException(ErrorCode.TICKET_NOT_FOUND));
        return mapToDetailResponse(ticket);
    }

    @Override
    @Transactional
    public PageResponse<List<TicketResponse>> getAll(String query, TicketStatus status, TicketType type,
                                                     Pageable pageable) {
        Page<Ticket> page = ticketRepository.findAll(buildSpec(null, query, status, type), pageable);
        return PageResponse.fromPage(page, this::mapToResponse);
    }

    @Override
    @Transactional
    public TicketDetailResponse getById(UUID id) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.TICKET_NOT_FOUND));
        return mapToDetailResponse(ticket);
    }

    @Override
    @Transactional
    public TicketDetailResponse update(UUID id, UpdateTicketRequest request) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.TICKET_NOT_FOUND));

        // CLOSED is terminal. Re-submitting the current status is allowed on purpose, so staff can
        // refine the resolution text without changing the status.
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new AppException(ErrorCode.TICKET_INVALID_STATUS_TRANSITION);
        }
        String resolution = request.resolution() == null ? null : request.resolution().trim();
        if (resolution != null && resolution.isEmpty()) {
            resolution = null;
        }
        if (request.status() == TicketStatus.RESOLVED && resolution == null && ticket.getResolution() == null) {
            throw new AppException(ErrorCode.TICKET_RESOLUTION_REQUIRED);
        }

        ticket.setStatus(request.status());
        if (resolution != null) {
            ticket.setResolution(resolution);
        }
        return mapToDetailResponse(ticketRepository.save(ticket));
    }

    /** All filters are optional; a null criterion is simply not added, so no null query parameters. */
    private Specification<Ticket> buildSpec(UUID userId, String query, TicketStatus status, TicketType type) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (userId != null) {
                predicates.add(cb.equal(root.get("user").get("id"), userId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (query != null && !query.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("title")), "%" + query.trim().toLowerCase() + "%"));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private TicketResponse mapToResponse(Ticket ticket) {
        return TicketResponse.builder()
                .id(ticket.getId())
                .messageId(ticket.getMessage().getId())
                .type(ticket.getType())
                .status(ticket.getStatus())
                .title(ticket.getTitle())
                .description(ticket.getDescription())
                .resolution(ticket.getResolution())
                .userId(ticket.getUser().getId())
                .userName(ticket.getUser().getFullName())
                .userEmail(ticket.getUser().getEmail())
                .createdAt(ticket.getCreatedAt())
                .updatedAt(ticket.getUpdatedAt())
                .build();
    }

    private TicketDetailResponse mapToDetailResponse(Ticket ticket) {
        Message message = ticket.getMessage();
        String question = messageRepository
                .findFirstByConversationIdAndRoleAndCreatedAtBeforeOrderByCreatedAtDesc(
                        message.getConversation().getId(), MsgRole.USER, message.getCreatedAt())
                .map(Message::getContent)
                .orElse(null);
        return TicketDetailResponse.builder()
                .id(ticket.getId())
                .messageId(message.getId())
                .conversationId(message.getConversation().getId())
                .type(ticket.getType())
                .status(ticket.getStatus())
                .title(ticket.getTitle())
                .description(ticket.getDescription())
                .resolution(ticket.getResolution())
                .userId(ticket.getUser().getId())
                .userName(ticket.getUser().getFullName())
                .userEmail(ticket.getUser().getEmail())
                .questionContent(question)
                .messageContent(message.getContent())
                .createdAt(ticket.getCreatedAt())
                .updatedAt(ticket.getUpdatedAt())
                .build();
    }
}

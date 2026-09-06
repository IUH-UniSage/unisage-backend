package com.unisage.backend.repository;

import com.unisage.backend.entity.ChatModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChatModelRepository extends JpaRepository<ChatModel, UUID> {

    @Override
    @EntityGraph(attributePaths = {"createdBy", "updatedBy"})
    Page<ChatModel> findAll(Pageable pageable);
}

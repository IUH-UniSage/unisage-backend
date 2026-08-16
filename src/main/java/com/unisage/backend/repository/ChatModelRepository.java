package com.unisage.backend.repository;

import com.unisage.backend.entity.ChatModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChatModelRepository extends JpaRepository<ChatModel, UUID> {
}

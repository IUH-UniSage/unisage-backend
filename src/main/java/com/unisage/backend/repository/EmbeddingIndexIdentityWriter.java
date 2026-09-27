package com.unisage.backend.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.hibernate.Session;
import org.springframework.stereotype.Repository;

import com.unisage.backend.entity.EmbeddingIndexIdentity;

/**
 * The only place allowed to write a new {@link EmbeddingIndexIdentity} row. Uses raw JDBC (not
 * {@code EntityManager#persist}) because the write has to be exactly
 * {@code INSERT ... ON CONFLICT (collection_name) DO NOTHING RETURNING collection_name} — never a
 * check-then-insert, which would race two concurrent first-upserts (plan.md "Embedding identity
 * guard"). There is no update or delete method here, and never will be.
 */
@Repository
public class EmbeddingIndexIdentityWriter {

    @PersistenceContext
    private EntityManager entityManager;

    /** @return true if this call established the identity, false if one already existed (caller maps that to 409). */
    public boolean insertIfAbsent(EmbeddingIndexIdentity identity) {
        Session session = entityManager.unwrap(Session.class);
        return session.doReturningWork(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO embedding_index_identity "
                            + "(collection_name, provider, model_name, model_source_ref, api_base_url, dimension, fingerprint, established_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (collection_name) DO NOTHING "
                            + "RETURNING collection_name")) {
                ps.setString(1, identity.getCollectionName());
                ps.setString(2, identity.getProvider());
                ps.setString(3, identity.getModelName());
                ps.setString(4, identity.getModelSourceRef());
                ps.setString(5, identity.getApiBaseUrl());
                ps.setInt(6, identity.getDimension());
                ps.setArray(7, connection.createArrayOf("real", identity.getFingerprint()));
                ps.setString(8, identity.getEstablishedBy());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }
}

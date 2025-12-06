package com.fusecode.assessment.repositories;

import com.fusecode.assessment.entities.IdempotencyKeysEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface IdempotencyKeysRepository extends JpaRepository<IdempotencyKeysEntity, Long> {
    Optional<IdempotencyKeysEntity> findByTenantIdAndKeyAndCreatedAtAfter(String tenantId, String key, Timestamp afterTime);

    boolean existsByKey(String key);
}

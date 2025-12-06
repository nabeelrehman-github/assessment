package com.fusecode.assessment.repositories;

import com.fusecode.assessment.entities.OrdersEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrdersRepository extends JpaRepository<OrdersEntity, UUID> {
    @Query("""
        SELECT o FROM OrdersEntity o
        WHERE o.tenantId = :tenantId
        AND (
            o.createdAt < :lastCreatedAt
            OR (o.createdAt = :lastCreatedAt AND o.id < :lastId)
        )
        ORDER BY o.createdAt DESC, o.id DESC
        """)
    List<OrdersEntity> findNextPage(
            @Param("tenantId") String tenantId,
            @Param("lastCreatedAt") Timestamp lastCreatedAt,
            @Param("lastId") UUID lastId,
            Pageable pageable
    );

    List<OrdersEntity> findByTenantIdOrderByCreatedAtDescIdDesc(
            String tenantId,
            Pageable pageable
    );
}

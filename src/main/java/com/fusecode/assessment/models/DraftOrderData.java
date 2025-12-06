package com.fusecode.assessment.models;

import com.fusecode.assessment.entities.OrdersEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DraftOrderData {
    private UUID id;
    private String tenantId;
    private String status;
    private int version;
    private LocalDateTime createdAt;

    public DraftOrderData(OrdersEntity entity) {
        this.id = entity.getId();
        this.tenantId = entity.getTenantId();
        this.status = entity.getStatus().name();
        this.version = entity.getVersion();
        this.createdAt = entity.getCreatedAt().toLocalDateTime();
    }
}

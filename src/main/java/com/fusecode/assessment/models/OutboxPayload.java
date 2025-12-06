package com.fusecode.assessment.models;

import com.fusecode.assessment.entities.OrdersEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OutboxPayload {
    private String orderId;
    private String tenantId;
    private Integer totalCents;
    private LocalDateTime closedAt;

    public OutboxPayload(OrdersEntity ordersEntity) {
        this.orderId = ordersEntity.getId().toString();
        this.tenantId = ordersEntity.getTenantId();
        this.totalCents = ordersEntity.getTotalCents();
        this.closedAt = ordersEntity.getUpdatedAt().toLocalDateTime();
    }
}

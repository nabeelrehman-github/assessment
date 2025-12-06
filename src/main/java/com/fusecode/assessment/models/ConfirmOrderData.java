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
public class ConfirmOrderData {
    private UUID id;
    private Integer totalCents;
    private String status;
    private int version;

    public ConfirmOrderData(OrdersEntity entity) {
        this.id = entity.getId();
        this.totalCents = entity.getTotalCents();
        this.status = entity.getStatus().name();
        this.version = entity.getVersion();
    }
}

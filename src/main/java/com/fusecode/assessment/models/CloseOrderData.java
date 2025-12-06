package com.fusecode.assessment.models;

import com.fusecode.assessment.entities.OrdersEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CloseOrderData {
    private UUID id;
    private String status;
    private int version;

    public CloseOrderData(OrdersEntity entity) {
        this.id = entity.getId();
        this.status = entity.getStatus().name();
        this.version = entity.getVersion();
    }
}

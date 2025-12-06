package com.fusecode.assessment.entities;

import com.fusecode.assessment.utils.Constants;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;
import java.util.UUID;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "orders")
public class OrdersEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String tenantId;
    @Enumerated(EnumType.STRING)
    private Constants.ORDER_STATUS status;
    private int version;
    private Integer totalCents;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}

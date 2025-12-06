package com.fusecode.assessment.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;
import java.util.UUID;

@Data
@Entity
@Builder
@Table(name = "outbox")
@AllArgsConstructor
@NoArgsConstructor
public class OutboxEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String eventType;
    private UUID orderId;
    private String tenantId;
    private String paylod;
    private Timestamp publishedAt;
}

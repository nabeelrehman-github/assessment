package com.fusecode.assessment.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.sql.Timestamp;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(
        name = "idempotency_keys",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_tenant_id_idempotency_key",
                columnNames = {"tenant_id", "idempotency_key"}
        )
)
public class IdempotencyKeysEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String tenantId;
    @Column(name = "idempotency_key")
    private String key;
    private String responseJson;
    @CreationTimestamp
    private Timestamp createdAt;
}

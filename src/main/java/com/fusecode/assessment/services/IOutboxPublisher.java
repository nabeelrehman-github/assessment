package com.fusecode.assessment.services;

import com.fusecode.assessment.entities.OutboxEntity;

/**
 * Interface for publishing outbox events to a message broker.
 * This is a placeholder interface - in a real implementation, this would
 * publish to Kafka, RabbitMQ, or another message broker.
 */
public interface IOutboxPublisher {

    /**
     * Publishes an outbox event to the message broker.
     *
     * @param outboxEntity The outbox entity containing the event to publish
     * @return true if the event was successfully published, false otherwise
     */
    static boolean publish(OutboxEntity outboxEntity) {
        return true;
    }

    /**
     * Marks an outbox event as published (typically after successful broker acknowledgment).
     * 
     * @param outboxId The ID of the outbox entity that was published
     */
    void markAsPublished(java.util.UUID outboxId);
}

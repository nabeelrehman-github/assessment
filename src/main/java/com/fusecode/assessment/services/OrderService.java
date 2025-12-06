package com.fusecode.assessment.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fusecode.assessment.entities.IdempotencyKeysEntity;
import com.fusecode.assessment.entities.OrdersEntity;
import com.fusecode.assessment.entities.OutboxEntity;
import com.fusecode.assessment.models.*;
import com.fusecode.assessment.repositories.IdempotencyKeysRepository;
import com.fusecode.assessment.repositories.OrdersRepository;
import com.fusecode.assessment.repositories.OutboxRepository;
import com.fusecode.assessment.utils.Constants;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrdersRepository ordersRepository;
    private final IdempotencyKeysRepository idempotencyKeysRepository;
    private final OutboxRepository outboxRepository;
    private final Tracer tracer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void postConstruct() {
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Transactional
    public ResponseEntity<DraftOrderResponse> createDraftOrder(BaseRequest request) throws JsonProcessingException {
        Span span = tracer.spanBuilder("order.createDraftOrder")
                .setAttribute("tenant.id", request.getTenantId())
                .setAttribute("idempotency.key", request.getKey())
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            log.info("Creating draft order");
            
            DraftOrderResponse response = new DraftOrderResponse();
            DraftOrderData data;

            // Check if same key and tenant id exists in last 1 hour, then return same response
            Timestamp oneHourAgo = Timestamp.from(
                    Instant.now().minus(1, ChronoUnit.HOURS)
            );
            Optional<IdempotencyKeysEntity> idempotency = idempotencyKeysRepository.findByTenantIdAndKeyAndCreatedAtAfter(request.getTenantId(), request.getKey(), oneHourAgo);

            if (idempotency.isPresent()) {
                span.setAttribute("idempotent.replay", true);
                log.info("Idempotent replay detected for key: {}", request.getKey());
                
                data = objectMapper.readValue(idempotency.get().getResponseJson(), DraftOrderData.class);

                response.setCode("200");
                response.setMessage("already created");
                response.setData(data);

                return ResponseEntity.status(HttpStatus.OK).body(response);
            }

            // Check if same key exists with different tenant id, then return 409 conflict response.
            if (idempotencyKeysRepository.existsByKey(request.getKey())) {
                span.setAttribute("error", true);
                span.setAttribute("error.type", "idempotency.conflict");
                log.warn("Idempotency key conflict detected: key={}, tenant={}", request.getKey(), request.getTenantId());
                
                response.setCode("409");
                response.setMessage("conflict");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }

            OrdersEntity draftOrder = OrdersEntity.builder()
                    .tenantId(request.getTenantId())
                    .status(Constants.ORDER_STATUS.DRAFT)
                    .version(1)
                    .createdAt(new Timestamp(System.currentTimeMillis()))
                    .updatedAt(new Timestamp(System.currentTimeMillis()))
                    .build();
            draftOrder = ordersRepository.save(draftOrder);

            span.setAttribute("order.id", draftOrder.getId().toString());
            span.setAttribute("order.status", draftOrder.getStatus().name());
            log.info("Draft order created successfully: orderId={}, tenantId={}", draftOrder.getId(), request.getTenantId());

            data = new DraftOrderData(draftOrder);

            IdempotencyKeysEntity idempotentEntity = IdempotencyKeysEntity.builder()
                    .key(request.getKey())
                    .tenantId(request.getTenantId())
                    .responseJson(objectMapper.writeValueAsString(data))
                    .build();

            idempotencyKeysRepository.save(idempotentEntity);

            response.setCode("201");
            response.setMessage("success");
            response.setData(data);

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            span.recordException(e);
            span.setAttribute("error", true);
            log.error("Error creating draft order", e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Transactional
    public ResponseEntity<ConfirmOrderResponse> confirmOrder(ConfirmOrderRequest request) {
        Span span = tracer.spanBuilder("order.confirmOrder")
                .setAttribute("order.id", request.getId())
                .setAttribute("tenant.id", request.getTenantId())
                .setAttribute("if.match", request.getIfMatch())
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            log.info("Confirming order: orderId={}, ifMatch={}", request.getId(), request.getIfMatch());
            
            ConfirmOrderResponse response = new ConfirmOrderResponse();
            Optional<OrdersEntity> byUUID = ordersRepository.findById(UUID.fromString(request.getId()));

            if (byUUID.isEmpty()) {
                span.setAttribute("error", true);
                span.setAttribute("error.type", "order.not.found");
                log.warn("Order not found: orderId={}", request.getId());
                
                response.setCode("404");
                response.setMessage("not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            OrdersEntity ordersEntity = byUUID.get();
            span.setAttribute("order.current.version", ordersEntity.getVersion());

            if (ordersEntity.getVersion() == request.getIfMatch()) {
                ordersEntity.setTotalCents(request.getTotalCents());
                ordersEntity.setStatus(Constants.ORDER_STATUS.CONFIRMED);
                ordersEntity.setVersion(ordersEntity.getVersion() + 1);
                ordersEntity.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
                ordersRepository.save(ordersEntity);

                span.setAttribute("order.new.version", ordersEntity.getVersion());
                span.setAttribute("order.status", ordersEntity.getStatus().name());
                log.info("Order confirmed successfully: orderId={}, version={}, totalCents={}", 
                    request.getId(), ordersEntity.getVersion(), request.getTotalCents());

                response.setCode("200");
                response.setMessage("success");
                response.setData(new ConfirmOrderData(ordersEntity));

                return ResponseEntity.ok(response);
            } else {
                span.setAttribute("error", true);
                span.setAttribute("error.type", "optimistic.locking.conflict");
                log.warn("Stale version detected: orderId={}, currentVersion={}, requestedVersion={}", 
                    request.getId(), ordersEntity.getVersion(), request.getIfMatch());
                
                response.setCode("409");
                response.setMessage("stale version");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }
        } catch (Exception e) {
            span.recordException(e);
            span.setAttribute("error", true);
            log.error("Error confirming order: orderId={}", request.getId(), e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Transactional
    public ResponseEntity<CloseOrderResponse> closeOrder(CloseOrderRequest request) throws JsonProcessingException {
        Span span = tracer.spanBuilder("order.closeOrder")
                .setAttribute("order.id", request.getId())
                .setAttribute("tenant.id", request.getTenantId())
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            log.info("Closing order: orderId={}", request.getId());
            
            CloseOrderResponse response = new CloseOrderResponse();
            Optional<OrdersEntity> byUUID = ordersRepository.findById(UUID.fromString(request.getId()));

            if (byUUID.isEmpty()) {
                span.setAttribute("error", true);
                span.setAttribute("error.type", "order.not.found");
                log.warn("Order not found: orderId={}", request.getId());
                
                response.setCode("404");
                response.setMessage("not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            OrdersEntity ordersEntity = byUUID.get();
            span.setAttribute("order.current.status", ordersEntity.getStatus().name());

            if (ordersEntity.getStatus().equals(Constants.ORDER_STATUS.CONFIRMED)) {
                ordersEntity.setStatus(Constants.ORDER_STATUS.CLOSED);
                ordersEntity.setVersion(ordersEntity.getVersion() + 1);
                ordersEntity.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
                ordersRepository.save(ordersEntity);

                span.setAttribute("order.new.status", ordersEntity.getStatus().name());
                span.setAttribute("order.new.version", ordersEntity.getVersion());

                OutboxPayload payload = new OutboxPayload(ordersEntity);

                OutboxEntity outboxEntity = OutboxEntity.builder()
                        .paylod(objectMapper.writeValueAsString(payload))
                        .orderId(ordersEntity.getId())
                        .eventType("orders.closed")
                        .tenantId(request.getTenantId())
                        .publishedAt(new Timestamp(System.currentTimeMillis()))
                        .build();
                outboxRepository.save(outboxEntity);

                span.setAttribute("outbox.id", outboxEntity.getId().toString());
                span.setAttribute("outbox.event.type", outboxEntity.getEventType());
                
                // Publish to outbox publisher (simulated - no real broker)
                boolean published = IOutboxPublisher.publish(outboxEntity);
                span.setAttribute("outbox.published", published);
                
                log.info("Order closed and outbox event created: orderId={}, outboxId={}, published={}", 
                    ordersEntity.getId(), outboxEntity.getId(), published);

                response.setCode("200");
                response.setMessage("success");
                response.setData(new CloseOrderData(ordersEntity));

                return ResponseEntity.ok(response);
            } else {
                span.setAttribute("error", true);
                span.setAttribute("error.type", "invalid.order.status");
                log.warn("Invalid order status for closing: orderId={}, status={}", 
                    request.getId(), ordersEntity.getStatus());
                
                response.setCode("409");
                response.setMessage("stale status");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }
        } catch (Exception e) {
            span.recordException(e);
            span.setAttribute("error", true);
            log.error("Error closing order: orderId={}", request.getId(), e);
            throw e;
        } finally {
            span.end();
        }
    }

    public ResponseEntity<CursorResponse> fetchOrders(PaginationRequest request) throws IOException, ParseException {
        Span span = tracer.spanBuilder("order.fetchOrders")
                .setAttribute("tenant.id", request.getTenantId())
                .setAttribute("limit", request.getLimit())
                .setAttribute("has.cursor", request.getCursor() != null && !request.getCursor().isEmpty())
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            log.info("Fetching orders: tenantId={}, limit={}, hasCursor={}", 
                request.getTenantId(), request.getLimit(), request.getCursor() != null);
            
            CursorResponse response = new CursorResponse();
            Pageable pageable = PageRequest.of(0, request.getLimit());

            List<OrdersEntity> listing;
            CursorValue cv = new CursorValue();

            String nextCursor = null;
            if (request.getCursor() == null || request.getCursor().isEmpty()) {
                listing = ordersRepository.findByTenantIdOrderByCreatedAtDescIdDesc(request.getTenantId(), pageable);
            } else {
                cv = objectMapper.readValue(Base64.getDecoder().decode(request.getCursor()), CursorValue.class);

                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
                Date parsedDate = dateFormat.parse(cv.getTs());
                Timestamp timestamp = new java.sql.Timestamp(parsedDate.getTime());

                listing = ordersRepository.findNextPage(request.getTenantId(), timestamp, UUID.fromString(cv.getId()), pageable);
            }

            if (!listing.isEmpty()) {
                cv.setId(listing.getLast().getId().toString());
                cv.setTs(String.valueOf(listing.getLast().getCreatedAt()));

                nextCursor = listing.isEmpty()
                        ? null
                        : objectMapper.writeValueAsString(cv);
                if (nextCursor != null)
                    nextCursor = Base64.getEncoder().encodeToString(nextCursor.getBytes());
            }

            span.setAttribute("orders.count", listing.size());
            span.setAttribute("has.next.cursor", nextCursor != null);
            log.info("Orders fetched successfully: tenantId={}, count={}, hasNextCursor={}", 
                request.getTenantId(), listing.size(), nextCursor != null);

            response.setCode("200");
            response.setMessage("success");
            response.setData(new CursorData(listing, nextCursor));

            return ResponseEntity.status(HttpStatus.OK).body(response);
        } catch (Exception e) {
            span.recordException(e);
            span.setAttribute("error", true);
            log.error("Error fetching orders: tenantId={}", request.getTenantId(), e);
            throw e;
        } finally {
            span.end();
        }
    }
}

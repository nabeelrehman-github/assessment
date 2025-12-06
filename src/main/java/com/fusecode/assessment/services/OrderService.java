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
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
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

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrdersRepository ordersRepository;
    private final IdempotencyKeysRepository idempotencyKeysRepository;
    private final OutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void postConstruct() {
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Transactional
    public ResponseEntity<DraftOrderResponse> createDraftOrder(BaseRequest request) throws JsonProcessingException {
        DraftOrderResponse response = new DraftOrderResponse();
        DraftOrderData data;

        // Check if same key and tenant id exists in last 1 hour, then return same response
        Timestamp oneHourAgo = Timestamp.from(
                Instant.now().minus(1, ChronoUnit.HOURS)
        );
        Optional<IdempotencyKeysEntity> idempotency = idempotencyKeysRepository.findByTenantIdAndKeyAndCreatedAtAfter(request.getTenantId(), request.getKey(), oneHourAgo);

        if (idempotency.isPresent()) {
            data = objectMapper.readValue(idempotency.get().getResponseJson(), DraftOrderData.class);

            response.setCode("200");
            response.setMessage("already created");
            response.setData(data);

            return ResponseEntity.status(HttpStatus.OK).body(response);
        }

        // Check if same key exists with different tenant id, then return 409 conflict response.
        if (idempotencyKeysRepository.existsByKey(request.getKey())) {
            response.setCode("409");
            response.setMessage("conflict");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        OrdersEntity draftOrder = OrdersEntity.builder().tenantId(request.getTenantId()).status(Constants.ORDER_STATUS.DRAFT).version(1).createdAt(new Timestamp(System.currentTimeMillis())).updatedAt(new Timestamp(System.currentTimeMillis())).build();
        draftOrder = ordersRepository.save(draftOrder);

        System.out.println(objectMapper.writeValueAsString(draftOrder));

        data = new DraftOrderData(draftOrder);

        IdempotencyKeysEntity idempotentEntity = IdempotencyKeysEntity.builder().key(request.getKey()).tenantId(request.getTenantId()).responseJson(objectMapper.writeValueAsString(data)).build();

        idempotencyKeysRepository.save(idempotentEntity);

        response.setCode("201");
        response.setMessage("success");
        response.setData(data);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Transactional
    public ResponseEntity<ConfirmOrderResponse> confirmOrder(ConfirmOrderRequest request) {
        ConfirmOrderResponse response = new ConfirmOrderResponse();
        Optional<OrdersEntity> byUUID = ordersRepository.findById(UUID.fromString(request.getId()));

        if (byUUID.isEmpty()) {
            response.setCode("404");
            response.setMessage("not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }

        if (byUUID.get().getVersion() == request.getIfMatch()) {
            OrdersEntity ordersEntity = byUUID.get();

            ordersEntity.setTotalCents(request.getTotalCents());
            ordersEntity.setStatus(Constants.ORDER_STATUS.CONFIRMED);
            ordersEntity.setVersion(ordersEntity.getVersion() + 1);
            ordersEntity.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            ordersRepository.save(ordersEntity);

            response.setCode("200");
            response.setMessage("success");
            response.setData(new ConfirmOrderData(ordersEntity));

            return ResponseEntity.ok(response);
        } else {
            response.setCode("409");
            response.setMessage("stale version");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
    }

    @Transactional
    public ResponseEntity<CloseOrderResponse> closeOrder(CloseOrderRequest request) throws JsonProcessingException {
        CloseOrderResponse response = new CloseOrderResponse();
        Optional<OrdersEntity> byUUID = ordersRepository.findById(UUID.fromString(request.getId()));

        if (byUUID.isEmpty()) {
            response.setCode("404");
            response.setMessage("not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }

        if (byUUID.get().getStatus().equals(Constants.ORDER_STATUS.CONFIRMED)) {
            OrdersEntity ordersEntity = byUUID.get();

            ordersEntity.setStatus(Constants.ORDER_STATUS.CLOSED);
            ordersEntity.setVersion(ordersEntity.getVersion() + 1);
            ordersEntity.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            ordersRepository.save(ordersEntity);

            OutboxPayload payload = new OutboxPayload(ordersEntity);

            OutboxEntity outboxEntity = OutboxEntity.builder().paylod(objectMapper.writeValueAsString(payload)).orderId(ordersEntity.getId()).eventType("orders.closed").tenantId(request.getTenantId()).publishedAt(new Timestamp(System.currentTimeMillis())).build();
            outboxRepository.save(outboxEntity);

            response.setCode("200");
            response.setMessage("success");
            response.setData(new CloseOrderData(ordersEntity));

            return ResponseEntity.ok(response);
        } else {
            response.setCode("409");
            response.setMessage("stale status");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
    }

    public ResponseEntity<CursorResponse> fetchOrders(PaginationRequest request) throws IOException, ParseException {
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

        response.setCode("200");
        response.setMessage("success");
        response.setData(new CursorData(listing, nextCursor));

        return ResponseEntity.status(HttpStatus.OK).body(response);
    }
}

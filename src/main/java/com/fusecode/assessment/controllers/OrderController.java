package com.fusecode.assessment.controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fusecode.assessment.models.*;
import com.fusecode.assessment.services.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.repository.query.Param;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.text.ParseException;

@RestController
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/orders")
    public ResponseEntity<DraftOrderResponse> createOrder(
            @RequestHeader(value = "X-Tenant-Id") String tenantId,
            @RequestHeader(value = "Idempotency-Key") String key
    ) throws JsonProcessingException {
        return orderService.createDraftOrder(new BaseRequest(key, tenantId));
    }

    @PatchMapping("/orders/{id}/confirm")
    public ResponseEntity<ConfirmOrderResponse> confirmOrder(
            @RequestHeader(value = "X-Tenant-Id") String tenantId,
            @RequestHeader(value = "Idempotency-Key") String key,
            @RequestHeader(value = "If-Match") Integer ifMatch,
            @PathVariable("id") String id,
            @RequestBody ConfirmOrderRequest request
    ) {
        request.setIfMatch(ifMatch);
        request.setKey(key);
        request.setTenantId(tenantId);
        request.setId(id);
        return orderService.confirmOrder(request);
    }

    @PostMapping("/orders/{id}/close")
    public ResponseEntity<CloseOrderResponse> closesOrder(
            @RequestHeader(value = "X-Tenant-Id") String tenantId,
            @RequestHeader(value = "Idempotency-Key") String key,
            @PathVariable("id") String id
    ) throws JsonProcessingException {
        CloseOrderRequest request = new CloseOrderRequest();
        request.setKey(key);
        request.setTenantId(tenantId);
        request.setId(id);
        return orderService.closeOrder(request);
    }

    @GetMapping("/orders")
    public ResponseEntity<CursorResponse> fetchOrders(
            @RequestHeader(value = "X-Tenant-Id") String tenantId,
            @Param("limit") String limit,
            @Param("cursor") String cursor
    ) throws IOException, ParseException {
        PaginationRequest request = new PaginationRequest();
        request.setTenantId(tenantId);
        request.setLimit(Integer.parseInt(limit));
        request.setCursor(cursor);
        return orderService.fetchOrders(request);
    }
}

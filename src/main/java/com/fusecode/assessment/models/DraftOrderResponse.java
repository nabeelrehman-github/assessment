package com.fusecode.assessment.models;

import lombok.*;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class DraftOrderResponse extends BaseResponse {
    private DraftOrderData data;
}
package com.fusecode.assessment.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class CloseOrderRequest extends BaseRequest {
    private String id;
}

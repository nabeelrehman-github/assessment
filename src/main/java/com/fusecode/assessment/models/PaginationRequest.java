package com.fusecode.assessment.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class PaginationRequest extends BaseRequest {
    private String cursor;
    private int limit;
}

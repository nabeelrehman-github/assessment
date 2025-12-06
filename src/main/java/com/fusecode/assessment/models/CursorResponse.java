package com.fusecode.assessment.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class CursorResponse extends BaseResponse {
    private CursorData data;
}

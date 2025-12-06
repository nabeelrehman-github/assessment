package com.fusecode.assessment.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ConfirmOrderRequest extends BaseRequest {
    @JsonIgnore
    private Integer ifMatch;
    @JsonIgnore
    private String id;
    private Integer totalCents;
}

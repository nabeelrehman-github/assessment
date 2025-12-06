package com.fusecode.assessment.models;

import com.fusecode.assessment.entities.OrdersEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CursorData {
    private List<OrdersEntity> items;
    private String nextCursor;
}

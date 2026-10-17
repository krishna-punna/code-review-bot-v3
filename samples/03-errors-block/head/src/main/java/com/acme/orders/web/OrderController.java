package com.acme.orders.web;

import com.acme.orders.model.Order;
import com.acme.orders.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoint for order lookups. */
@RestController
public class OrderController {

    @Autowired
    private OrderRepository orders;

    @GetMapping("/orders/{id}")
    public ResponseEntity<Order> get(@PathVariable String id) {
        System.out.println("GET /orders/" + id);
        try {
            return orders.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
        } catch (RuntimeException e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }
}

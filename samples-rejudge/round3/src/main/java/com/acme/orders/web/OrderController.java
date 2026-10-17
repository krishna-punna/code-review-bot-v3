package com.acme.orders.web;

import com.acme.orders.audit.AuditLog;
import com.acme.orders.model.Order;
import com.acme.orders.service.OrderLookupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoint for order lookups. */
@RestController
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderLookupService orders;
    private final AuditLog audit;

    public OrderController(OrderLookupService orders, AuditLog audit) {
        this.orders = orders;
        this.audit = audit;
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<Order> get(@PathVariable String id) {
        log.debug("GET /orders/{}", id);
        audit.record("order-lookup", id);
        try {
            return orders.find(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
        } catch (RuntimeException e) {
            log.error("Order lookup failed for {}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}

package com.acme.orders.service;

import com.acme.orders.model.Order;
import com.acme.orders.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Looks up orders for the web layer, so controllers never touch the repository. */
@Service
public class OrderLookupService {

    private static final Logger log = LoggerFactory.getLogger(OrderLookupService.class);

    private final OrderRepository orders;

    public OrderLookupService(OrderRepository orders) {
        this.orders = orders;
    }

    public Optional<Order> find(String orderId) {
        log.debug("Looking up order {}", orderId);
        return orders.findById(orderId);
    }
}

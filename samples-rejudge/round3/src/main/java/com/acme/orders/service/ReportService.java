package com.acme.orders.service;

import com.acme.orders.repository.OrderRepository;
import org.springframework.stereotype.Service;

/** Counts today's orders for the daily report. */
@Service
public class ReportService {

    private final OrderRepository orders;

    public ReportService(OrderRepository orders) {
        this.orders = orders;
    }

    public long dailyOrderCount() {
        return orders.count();
    }
}

package com.acme.orders.service;

import com.acme.orders.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Counts today's orders for the daily report. */
@Service
public class ReportService {

    @Autowired
    private OrderRepository orders;

    public long dailyOrderCount() {
        return orders.count();
    }
}

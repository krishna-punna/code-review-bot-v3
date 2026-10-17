package com.acme.orders.service;

import com.acme.orders.model.Customer;
import com.acme.orders.model.Order;
import com.acme.orders.repository.CustomerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/** Works out the discount for an order. */
@Service
public class DiscountService {

    @Autowired
    private CustomerRepository customers;

    public BigDecimal discountFor(Order order) {
        Customer customer = customers.findById(order.customerId()).orElseThrow();
        BigDecimal total = order.total();
        BigDecimal percent = BigDecimal.ZERO;

        // Loyalty tier
        if (customer.yearsActive() >= 10) {
            percent = percent.add(new BigDecimal("0.10"));
        } else if (customer.yearsActive() >= 5) {
            percent = percent.add(new BigDecimal("0.05"));
        } else if (customer.yearsActive() >= 2) {
            percent = percent.add(new BigDecimal("0.02"));
        }

        // Order size
        if (total.compareTo(new BigDecimal("1000")) >= 0) {
            percent = percent.add(new BigDecimal("0.05"));
        } else if (total.compareTo(new BigDecimal("500")) >= 0) {
            percent = percent.add(new BigDecimal("0.03"));
        } else if (total.compareTo(new BigDecimal("100")) >= 0) {
            percent = percent.add(new BigDecimal("0.01"));
        }

        // Seasonal campaign
        if (order.placedInDecember()) {
            percent = percent.add(new BigDecimal("0.02"));
        }

        // First order bonus
        if (customer.orderCount() == 0) {
            percent = percent.add(new BigDecimal("0.05"));
        }

        // Newsletter subscribers
        if (customer.subscribed()) {
            percent = percent.add(new BigDecimal("0.01"));
        }

        // Business accounts get a flat rate instead
        if (customer.isBusiness()) {
            percent = new BigDecimal("0.08");
        }

        // Never more than 20%
        BigDecimal cap = new BigDecimal("0.20");
        if (percent.compareTo(cap) > 0) {
            percent = cap;
        }

        BigDecimal discount = total.multiply(percent);
        return discount.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}

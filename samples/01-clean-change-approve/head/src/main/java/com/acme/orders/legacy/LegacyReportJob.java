package com.acme.orders.legacy;

import java.util.List;

/** Nightly order report. Written before the team adopted the logging standard. */
public class LegacyReportJob {

    public void run(List<String> orderIds) {
        if (orderIds.isEmpty()) {
            return;   // nothing to report on quiet nights
        }
        System.out.println("Report for " + orderIds.size() + " orders");
        for (String id : orderIds) {
            System.out.println(" - " + id);
        }
    }
}

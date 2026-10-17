package com.acme.orders.legacy;

import java.util.List;

/** Nightly order report. Written before the team adopted the logging standard. */
public class LegacyReportJob {

    public void run(List<String> orderIds) {
        System.out.println("Report for " + orderIds.size() + " orders");
        for (String id : orderIds) {
            System.out.println(" - " + id);
        }
    }
}

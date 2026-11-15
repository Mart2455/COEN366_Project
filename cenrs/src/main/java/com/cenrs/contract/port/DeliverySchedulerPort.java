package com.cenrs.contract.port;

import java.time.Duration;

public interface DeliverySchedulerPort {
    ScheduledDelivery schedule(Duration delay, Runnable task);

    interface ScheduledDelivery {
        boolean cancel();

        boolean isCancelled();
    }
}
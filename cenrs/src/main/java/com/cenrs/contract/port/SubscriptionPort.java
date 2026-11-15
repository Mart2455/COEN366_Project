package com.cenrs.contract.port;

import com.cenrs.contract.Subscription;
import java.util.Set;

public interface SubscriptionPort {
    boolean add(String clientName, Subscription subscription);

    boolean remove(String clientName, Subscription subscription);

    Set<Subscription> findByClient(String clientName);
}
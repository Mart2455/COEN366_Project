package com.cenrs.contract.fixture;

import com.cenrs.contract.AlertCategory;
import com.cenrs.contract.CampusLocation;
import com.cenrs.contract.ClientProfile;
import com.cenrs.contract.ClientRole;
import com.cenrs.contract.Incident;
import com.cenrs.contract.IncidentStatus;
import com.cenrs.contract.Severity;
import com.cenrs.contract.Subscription;

public final class ContractFixtures {
    public static final String REGISTER_FRAME =
        "REGISTER|1|Alice|SUBSCRIBER|127.0.0.1|5001|6001";

    public static final String SUBSCRIBE_FRAME =
        "SUBSCRIBE|2|HALL|FIRE";

    private ContractFixtures() {
    }

    public static ClientProfile subscriber() {
        return new ClientProfile(
            "Alice",
            ClientRole.SUBSCRIBER,
            "127.0.0.1",
            5001,
            6001
        );
    }

    public static Subscription hallFireSubscription() {
        return new Subscription(
            CampusLocation.HALL,
            AlertCategory.FIRE
        );
    }

    public static Incident activeIncident() {
        return new Incident(
            "INC-1",
            1,
            AlertCategory.FIRE,
            CampusLocation.HALL,
            Severity.CRITICAL,
            IncidentStatus.ACTIVE,
            "Fire alarm",
            "Smoke reported on the third floor",
            "NONE",
            "Bob"
        );
    }
}
package com.cenrs.contract;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ContractModelTest {
    @Test
    public void rolesExposeTheirPermissions() {
        assertTrue(ClientRole.SUBSCRIBER.canSubscribe());
        assertFalse(ClientRole.SUBSCRIBER.canPublish());

        assertTrue(ClientRole.PUBLISHER.canPublish());
        assertFalse(ClientRole.PUBLISHER.canSubscribe());

        assertTrue(ClientRole.BOTH.canSubscribe());
        assertTrue(ClientRole.BOTH.canPublish());
    }

    @Test
    public void subscriptionMatchesExactValuesAndWildcards() {
        Subscription exact = new Subscription(
            CampusLocation.HALL,
            AlertCategory.FIRE
        );
        Subscription allLocations = new Subscription(
            CampusLocation.ALL,
            AlertCategory.FIRE
        );
        Subscription allCategories = new Subscription(
            CampusLocation.HALL,
            AlertCategory.ALL
        );
        Subscription everything = new Subscription(
            CampusLocation.ALL,
            AlertCategory.ALL
        );

        assertTrue(exact.matches(
            CampusLocation.HALL,
            AlertCategory.FIRE
        ));
        assertFalse(exact.matches(
            CampusLocation.EV,
            AlertCategory.FIRE
        ));
        assertTrue(allLocations.matches(
            CampusLocation.JMSB,
            AlertCategory.FIRE
        ));
        assertTrue(allCategories.matches(
            CampusLocation.HALL,
            AlertCategory.POWER
        ));
        assertTrue(everything.matches(
            CampusLocation.SGW,
            AlertCategory.WEATHER
        ));
    }

    @Test
    public void onlyCriticalSeverityRequiresAcknowledgement() {
        assertFalse(Severity.INFO.requiresAcknowledgement());
        assertFalse(Severity.WARNING.requiresAcknowledgement());
        assertTrue(Severity.CRITICAL.requiresAcknowledgement());
    }

    @Test
    public void incidentReportsWhetherItHasAnAttachment() {
        Incident withoutAttachment = new Incident(
            "INC1",
            1,
            AlertCategory.FIRE,
            CampusLocation.HALL,
            Severity.CRITICAL,
            IncidentStatus.ACTIVE,
            "Fire alarm",
            "Evacuate the building",
            "NONE",
            "CampusSecurity"
        );

        Incident withAttachment = new Incident(
            "INC2",
            1,
            AlertCategory.WEATHER,
            CampusLocation.SGW,
            Severity.WARNING,
            IncidentStatus.ACTIVE,
            "Storm warning",
            "Remain indoors",
            "weather_map.pdf",
            "CampusSecurity"
        );

        assertFalse(withoutAttachment.hasAttachment());
        assertTrue(withAttachment.hasAttachment());
    }

    @Test(expected = IllegalArgumentException.class)
    public void clientProfileRejectsInvalidPort() {
        new ClientProfile(
            "Alice",
            ClientRole.SUBSCRIBER,
            "127.0.0.1",
            0,
            6001
        );
    }
}
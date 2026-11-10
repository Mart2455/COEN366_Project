package com.cenrs.contract;

import java.util.Objects;

public record Subscription(
    CampusLocation location,
    AlertCategory category
) {
    public Subscription {
        location = Objects.requireNonNull(
            location,
            "location cannot be null"
        );
        category = Objects.requireNonNull(
            category,
            "category cannot be null"
        );
    }

    public boolean matches(
        CampusLocation alertLocation,
        AlertCategory alertCategory
    ) {
        Objects.requireNonNull(alertLocation, "alert location cannot be null");
        Objects.requireNonNull(alertCategory, "alert category cannot be null");

        boolean locationMatches =
            location == CampusLocation.ALL || location == alertLocation;

        boolean categoryMatches =
            category == AlertCategory.ALL || category == alertCategory;

        return locationMatches && categoryMatches;
    }
}
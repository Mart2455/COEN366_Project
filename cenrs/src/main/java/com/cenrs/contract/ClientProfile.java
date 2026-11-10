package com.cenrs.contract;

import java.util.Objects;

public record ClientProfile(
    String name,
    ClientRole role,
    String ipAddress,
    int udpPort,
    int tcpPort
) {
    public ClientProfile {
        name = ContractValidation.requiredText(name, "name", 64);
        role = Objects.requireNonNull(role, "role cannot be null");
        ipAddress = ContractValidation.requiredText(
            ipAddress,
            "IP address",
            128
        );
        udpPort = ContractValidation.validPort(udpPort, "UDP port");
        tcpPort = ContractValidation.validPort(tcpPort, "TCP port");
    }
}
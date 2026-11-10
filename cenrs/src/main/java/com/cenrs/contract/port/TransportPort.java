package com.cenrs.contract.port;

import java.io.IOException;
import java.net.InetSocketAddress;

public interface TransportPort {
    void send(
        InetSocketAddress destination,
        byte[] message
    ) throws IOException;
}
package org.example.p2pfileshare.network.transport;

import org.example.p2prelay.protocol.RelayProtocol;

/** Loại kênh: tương ứng cổng điều khiển và cổng truyền file trong LAN */
public enum Channel {
    CONTROL(RelayProtocol.CH_CONTROL),
    FILE(RelayProtocol.CH_FILE);

    private final String wireName;

    Channel(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Channel fromWire(String name) {
        for (Channel c : values()) {
            if (c.wireName.equals(name)) return c;
        }
        return null;
    }
}

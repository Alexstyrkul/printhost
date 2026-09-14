package dev.oleksandr.printhost;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** Registry of connected WebSocket clients. Push-only: the dashboard used to poll GET /status
 *  every 2 seconds (a fresh TCP connection each time - see StateBroadcasterThread's own comment
 *  on why that mattered); this hub is what lets PrinterService instead push a state snapshot out
 *  over one long-lived connection whenever something actually changes. */
class WsHub {

    private final Set<WsConnection> connections = new CopyOnWriteArraySet<>();

    void register(WsConnection conn) {
        connections.add(conn);
    }

    void unregister(WsConnection conn) {
        connections.remove(conn);
    }

    boolean hasConnections() {
        return !connections.isEmpty();
    }

    void broadcast(String text) {
        for (WsConnection conn : connections) {
            if (conn.isClosed()) {
                connections.remove(conn);
                continue;
            }
            conn.send(text);
        }
    }
}

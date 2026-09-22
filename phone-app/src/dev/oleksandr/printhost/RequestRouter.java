package dev.oleksandr.printhost;

import java.io.IOException;
import java.io.OutputStream;

/** Implemented by whoever owns the actual routes (DashboardRouter). Must write a complete,
 *  well-formed HTTP response (status line + headers + body) to `out` before returning -
 *  including for streaming routes, which just keep writing until the connection drops. */
public interface RequestRouter {
    void handle(HttpRequest request, OutputStream out) throws IOException;
}

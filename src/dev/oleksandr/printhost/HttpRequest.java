package dev.oleksandr.printhost;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class HttpRequest {
    public String method = "";
    public String path = "";
    public final Map<String, String> query = new HashMap<String, String>();
    public final Map<String, String> headers = new HashMap<String, String>();
    public long contentLength = 0;
    public InputStream body;

    public String queryParam(String name) {
        String v = query.get(name);
        return v == null ? "" : v;
    }
}

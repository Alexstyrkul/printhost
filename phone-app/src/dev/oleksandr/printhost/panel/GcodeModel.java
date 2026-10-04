package dev.oleksandr.printhost.panel;

import dev.oleksandr.printhost.R;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.TreeSet;

/**
 * The extrusion moves of a gcode file as line segments for the 3D preview: 4 floats per vertex (x, y, z, shade),
 * two vertices per segment, plus the height of every layer. The shade is positive for outer walls and negative for
 * everything else (inner walls, infill...), taken from the slicer's ";TYPE:" comments: the part still to print is
 * shown as its outer shell only. A file without those comments counts as all outer walls.
 */
final class GcodeModel {
    float[] verts = new float[1 << 18];
    int floats = 0;
    float[] layerZ = new float[0];
    float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = 0;

    int vertexCount() {
        return floats / 4;
    }

    static GcodeModel parse(InputStream in) throws IOException {
        GcodeModel m = new GcodeModel();
        TreeSet<Integer> layers = new TreeSet<Integer>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "US-ASCII"), 1 << 16);
        float x = 0, y = 0, z = 0, e = 0;
        boolean absXyz = true, absE = true, outer = true;
        float[] p = new float[4];  // X Y Z E as read from the line
        boolean[] has = new boolean[4];
        String line;
        while ((line = r.readLine()) != null) {
            int n = line.length();
            if (n < 2) continue;
            char c0 = line.charAt(0);
            if (c0 == ';') {
                if (line.startsWith(";TYPE:")) {
                    String t = line.toLowerCase(java.util.Locale.US);
                    outer = t.contains("outer") || t.contains("external perimeter");
                }
                continue;
            }
            if (c0 == 'M') {
                if (line.startsWith("M82")) absE = true;
                else if (line.startsWith("M83")) absE = false;
                continue;
            }
            if (c0 != 'G') continue;
            int code = 0, i = 1;
            while (i < n && line.charAt(i) >= '0' && line.charAt(i) <= '9') code = code * 10 + (line.charAt(i++) - '0');
            if (code > 3 && code != 90 && code != 91 && code != 92) continue;
            if (code == 90) {
                absXyz = true;
                absE = true;
                continue;
            }
            if (code == 91) {
                absXyz = false;
                absE = false;
                continue;
            }
            has[0] = has[1] = has[2] = has[3] = false;
            while (i < n) {
                char c = line.charAt(i);
                if (c == ';') break;
                int k = c == 'X' ? 0 : c == 'Y' ? 1 : c == 'Z' ? 2 : c == 'E' ? 3 : -1;
                i++;
                if (k < 0) continue;
                int s = i;
                while (i < n) {
                    char d = line.charAt(i);
                    if ((d >= '0' && d <= '9') || d == '.' || d == '-' || d == '+') i++;
                    else break;
                }
                if (i > s) {
                    try {
                        p[k] = Float.parseFloat(line.substring(s, i));
                        has[k] = true;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (code == 92) {
                if (has[0]) x = p[0];
                if (has[1]) y = p[1];
                if (has[2]) z = p[2];
                if (has[3]) e = p[3];
                continue;
            }
            float nx = has[0] ? (absXyz ? p[0] : x + p[0]) : x;
            float ny = has[1] ? (absXyz ? p[1] : y + p[1]) : y;
            float nz = has[2] ? (absXyz ? p[2] : z + p[2]) : z;
            boolean extrudes = false;
            if (has[3]) {
                extrudes = absE ? p[3] > e : p[3] > 0;
                e = absE ? p[3] : e + p[3];
            }
            if (extrudes && (nx != x || ny != y) && nx >= 0 && ny >= 0) {  // nx/ny < 0: the purge line beside the bed
                float dx = nx - x, dy = ny - y;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                int layerKey = Math.round(nz * 1000);
                layers.add(layerKey);
                // Fake lighting: lines running one way are brighter than lines running the other, and every second
                // layer is a touch darker, so walls, infill and layers can be told apart.
                float shade = 0.62f + 0.38f * Math.abs(dx / len) * 0.6f + 0.38f * 0.4f * Math.abs(dy / len);
                if ((layers.size() & 1) == 0) shade *= 0.9f;
                if (!outer) shade = -shade;
                m.add(x, y, nz, shade);
                m.add(nx, ny, nz, shade);
                if (nx < m.minX) m.minX = nx;
                if (nx > m.maxX) m.maxX = nx;
                if (ny < m.minY) m.minY = ny;
                if (ny > m.maxY) m.maxY = ny;
                if (x < m.minX) m.minX = x;
                if (x > m.maxX) m.maxX = x;
                if (y < m.minY) m.minY = y;
                if (y > m.maxY) m.maxY = y;
                if (nz > m.maxZ) m.maxZ = nz;
            }
            x = nx;
            y = ny;
            z = nz;
        }
        m.layerZ = new float[layers.size()];
        int i = 0;
        for (int k : layers) m.layerZ[i++] = k / 1000f;
        return m;
    }

    private void add(float x, float y, float z, float shade) {
        if (floats + 4 > verts.length) verts = java.util.Arrays.copyOf(verts, verts.length * 2);
        verts[floats++] = x;
        verts[floats++] = y;
        verts[floats++] = z;
        verts[floats++] = shade;
    }
}

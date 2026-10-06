// Reads a gcode file and turns its extrusion moves into line segments for preview-lite.js. Runs as a Web Worker:
// a 70 MB file takes seconds to read and parse, and doing that on the page's own thread froze the whole dashboard.
//
// Out: Float32Array, 4 numbers per vertex, 2 vertices per segment: x, y, z, w.
//   w = +-(layer + 0.25 + shade * 0.5): layer = 1-based layer number, shade = 0..1 brightness,
//   the sign is + for outer walls and - for everything else (from the slicer's ";TYPE:" comments).
self.onmessage = async function (e) {
  try {
    const res = await fetch(e.data.url);
    if (!res.ok) throw new Error("HTTP " + res.status);
    const total = +res.headers.get("Content-Length") || 0;
    const reader = res.body.getReader();
    const dec = new TextDecoder();

    let buf = new Float32Array(1 << 20), n = 0, segs = 0;
    let x = 0, y = 0, z = 0, ex = 0, absXyz = true, absE = true, outer = true;
    let markers = 0;            // ";LAYER_CHANGE" comments seen so far (OrcaSlicer, PrusaSlicer...)
    let minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9, maxZ = 0;
    const p = [0, 0, 0, 0], has = [false, false, false, false];

    function line(s) {
      const len = s.length;
      if (len < 2) return;
      const c0 = s.charCodeAt(0);
      if (c0 === 59) {  // ;
        if (s.startsWith(";LAYER_CHANGE")) markers++;
        else if (s.startsWith(";TYPE:")) {
          const t = s.toLowerCase();
          outer = t.indexOf("outer") >= 0 || t.indexOf("external perimeter") >= 0;
        }
        return;
      }
      if (c0 === 77) {  // M
        if (s.startsWith("M82")) absE = true;
        else if (s.startsWith("M83")) absE = false;
        return;
      }
      if (c0 !== 71) return;  // G
      let i = 1, code = 0, d;
      while (i < len && (d = s.charCodeAt(i)) >= 48 && d <= 57) { code = code * 10 + d - 48; i++; }
      if (code > 3 && code !== 90 && code !== 91 && code !== 92) return;
      if (code === 90) { absXyz = true; absE = true; return; }
      if (code === 91) { absXyz = false; absE = false; return; }
      has[0] = has[1] = has[2] = has[3] = false;
      while (i < len) {
        const c = s.charCodeAt(i);
        if (c === 59) break;
        const k = c === 88 ? 0 : c === 89 ? 1 : c === 90 ? 2 : c === 69 ? 3 : -1;
        i++;
        if (k < 0) continue;
        const st = i;
        while (i < len) {
          d = s.charCodeAt(i);
          if ((d >= 48 && d <= 57) || d === 46 || d === 45 || d === 43) i++; else break;
        }
        if (i > st) { p[k] = +s.substring(st, i); has[k] = true; }
      }
      if (code === 92) {
        if (has[0]) x = p[0];
        if (has[1]) y = p[1];
        if (has[2]) z = p[2];
        if (has[3]) ex = p[3];
        return;
      }
      const nx = has[0] ? (absXyz ? p[0] : x + p[0]) : x;
      const ny = has[1] ? (absXyz ? p[1] : y + p[1]) : y;
      const nz = has[2] ? (absXyz ? p[2] : z + p[2]) : z;
      let extrudes = false;
      if (has[3]) {
        extrudes = absE ? p[3] > ex : p[3] > 0;
        ex = absE ? p[3] : ex + p[3];
      }
      if (extrudes && (nx !== x || ny !== y) && nx >= 0 && ny >= 0) {  // nx/ny < 0: the purge line beside the bed
        const dx = nx - x, dy = ny - y, l = Math.sqrt(dx * dx + dy * dy);
        const shade = 0.4 + 0.6 * (0.6 * Math.abs(dx / l) + 0.4 * Math.abs(dy / l));
        // layer for now: the marker count (0 = no markers yet: fixed up from Z at the end)
        const w = (outer ? 1 : -1) * (markers + 0.25 + shade * 0.5);
        if (n + 8 > buf.length) { const b = new Float32Array(buf.length * 2); b.set(buf); buf = b; }
        buf[n++] = x; buf[n++] = y; buf[n++] = nz; buf[n++] = w;
        buf[n++] = nx; buf[n++] = ny; buf[n++] = nz; buf[n++] = w;
        segs++;
        if (nx < minX) minX = nx; if (nx > maxX) maxX = nx;
        if (ny < minY) minY = ny; if (ny > maxY) maxY = ny;
        if (x < minX) minX = x; if (x > maxX) maxX = x;
        if (y < minY) minY = y; if (y > maxY) maxY = y;
        if (nz > maxZ) maxZ = nz;
      }
      x = nx; y = ny; z = nz;
    }

    let tail = "", received = 0, lastReport = 0;
    for (;;) {
      const r = await reader.read();
      if (r.done) break;
      received += r.value.length;
      const text = tail + dec.decode(r.value, { stream: true });
      let from = 0, nl;
      while ((nl = text.indexOf("\n", from)) >= 0) {
        line(text.substring(from, nl));
        from = nl + 1;
      }
      tail = text.substring(from);
      const now = Date.now();
      if (now - lastReport > 200) {
        lastReport = now;
        self.postMessage({ progress: total ? received / total : 0 });
      }
    }
    if (tail) line(tail);

    let layers = markers;
    if (markers === 0) {
      // No layer markers in the file: number the layers by the distinct heights that were printed at.
      const zs = new Set();
      for (let i = 2; i < n; i += 4) zs.add(Math.round(buf[i] * 1000));
      const sorted = Array.from(zs).sort(function (a, b) { return a - b; });
      const index = new Map();
      sorted.forEach(function (v, i) { index.set(v, i + 1); });
      for (let i = 0; i < n; i += 4) {
        const w = buf[i + 3], frac = Math.abs(w) - Math.floor(Math.abs(w));
        buf[i + 3] = (w < 0 ? -1 : 1) * (index.get(Math.round(buf[i + 2] * 1000)) + frac);
      }
      layers = sorted.length;
    }
    const out = buf.slice(0, n);
    self.postMessage({ verts: out, segments: segs, layers: layers, box: [minX, minY, maxX, maxY, maxZ] }, [out.buffer]);
  } catch (err) {
    self.postMessage({ error: String(err && err.message ? err.message : err) });
  }
};

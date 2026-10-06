// Light 3D preview for big gcode files: the extrusion moves as plain lines, parsed in a Web Worker (preview-worker.js)
// and drawn with a few lines of raw WebGL. The regular preview (preview-panel.js) builds real tube geometry for two
// scenes on the page's own thread - beautiful, but a 70 MB file froze the whole dashboard for good.
//
// Same idea as the panel on the phone: layers up to the current one are drawn solid in the filament colour, the rest
// as a see-through grey shell (outer walls only). Drag turns, the wheel zooms, a right-button or shift drag moves.
// Returns the same handle as initPrintPreview(): { totalLayers, setCurrentLayer }.
const BED = 220;
const VS = `
uniform mat4 uMvp; uniform float uLayer; uniform float uFlat; uniform vec3 uColor;
attribute vec4 aPos; varying vec3 vCol; varying float vTodo;
void main() {
  gl_Position = uMvp * vec4(aPos.xyz, 1.0);
  float a = abs(aPos.w), layer = floor(a), shade = fract(a) * 2.0 - 0.5;
  vTodo = (uFlat < 0.5 && layer > uLayer + 0.5) ? 1.0 : 0.0;
  if (vTodo > 0.5 && aPos.w < 0.0) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
  vCol = uFlat > 0.5 ? uColor : (vTodo > 0.5 ? vec3(0.72, 0.75, 0.80) : vec3(0.243, 0.812, 0.494) * (0.55 + 0.45 * shade));
}`;
const FS = `
precision mediump float; varying vec3 vCol; varying float vTodo; uniform float uPass;
void main() {
  if (uPass < 0.5 && vTodo > 0.5) discard;
  if (uPass > 0.5 && vTodo < 0.5) discard;
  gl_FragColor = vec4(vCol, vTodo > 0.5 ? 0.06 : 1.0);
}`;

function perspective(fovDeg, aspect, near, far) {
  const f = 1 / Math.tan(fovDeg * Math.PI / 360), nf = 1 / (near - far);
  return [f / aspect, 0, 0, 0, 0, f, 0, 0, 0, 0, (far + near) * nf, -1, 0, 0, 2 * far * near * nf, 0];
}
function lookAt(e, c, u) {
  let zx = e[0] - c[0], zy = e[1] - c[1], zz = e[2] - c[2];
  let l = Math.hypot(zx, zy, zz); zx /= l; zy /= l; zz /= l;
  let xx = u[1] * zz - u[2] * zy, xy = u[2] * zx - u[0] * zz, xz = u[0] * zy - u[1] * zx;
  l = Math.hypot(xx, xy, xz); xx /= l; xy /= l; xz /= l;
  const yx = zy * xz - zz * xy, yy = zz * xx - zx * xz, yz = zx * xy - zy * xx;
  return [xx, yx, zx, 0, xy, yy, zy, 0, xz, yz, zz, 0,
    -(xx * e[0] + xy * e[1] + xz * e[2]), -(yx * e[0] + yy * e[1] + yz * e[2]), -(zx * e[0] + zy * e[1] + zz * e[2]), 1];
}
function mul(a, b) {
  const o = new Float32Array(16);
  for (let c = 0; c < 4; c++) for (let r = 0; r < 4; r++) {
    o[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
  }
  return o;
}

export function initPrintPreviewLite({ canvas, gcodeUrl, initialLayer, onProgress }) {
  return new Promise(function (resolve, reject) {
    const worker = new Worker("/vendor/preview-worker.js");
    worker.onerror = function (e) { reject(new Error(e.message || "preview worker failed")); };
    worker.onmessage = function (e) {
      const m = e.data;
      if (m.error) { worker.terminate(); reject(new Error(m.error)); return; }
      if (m.progress != null) { if (onProgress) onProgress(m.progress); return; }
      worker.terminate();
      try {
        resolve(start(canvas, m, initialLayer || 0));
      } catch (err) {
        reject(err);
      }
    };
    worker.postMessage({ url: new URL(gcodeUrl, location.href).href });
  });
}

function start(canvas, model, initialLayer) {
  const gl = canvas.getContext("webgl", { antialias: true, alpha: false });
  if (!gl) throw new Error("WebGL is not available");
  function shader(type, src) {
    const s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
    return s;
  }
  const prog = gl.createProgram();
  gl.attachShader(prog, shader(gl.VERTEX_SHADER, VS));
  gl.attachShader(prog, shader(gl.FRAGMENT_SHADER, FS));
  gl.linkProgram(prog);
  gl.useProgram(prog);
  const aPos = gl.getAttribLocation(prog, "aPos");
  const U = {};
  ["uMvp", "uLayer", "uFlat", "uColor", "uPass"].forEach(function (n) { U[n] = gl.getUniformLocation(prog, n); });

  // bed: a plate and a 10 mm grid (heavier every 50 mm is left out: plain lines only)
  const bed = [];
  [[0, 0], [BED, 0], [BED, BED], [0, 0], [BED, BED], [0, BED]].forEach(function (q) { bed.push(q[0], q[1], -0.15, 1); });
  for (let t = 0; t <= BED; t += 10) bed.push(t, 0, -0.05, 1, t, BED, -0.05, 1, 0, t, -0.05, 1, BED, t, -0.05, 1);
  const bedLines = (bed.length / 4) - 6;
  const bedBuf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, bedBuf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(bed), gl.STATIC_DRAW);
  const modelBuf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, modelBuf);
  gl.bufferData(gl.ARRAY_BUFFER, model.verts, gl.STATIC_DRAW);
  const count = model.verts.length / 4;
  model.verts = null;  // the GPU has it now

  const box = model.box;
  const has = count > 0;
  const center = has ? [(box[0] + box[2]) / 2, (box[1] + box[3]) / 2, box[4] / 2] : [BED / 2, BED / 2, 0];
  const size = has ? Math.hypot(box[2] - box[0], box[3] - box[1], box[4]) : BED * 0.8;
  let yaw = -55, pitch = 28, zoom = 1, panR = 0, panU = 0, layer = initialLayer, dirty = true, mmPerPx = 0.5;

  function draw() {
    dirty = false;
    const w = canvas.clientWidth, h = canvas.clientHeight, dpr = Math.min(2, window.devicePixelRatio || 1);
    if (!w || !h) return;
    if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(h * dpr);
    }
    gl.viewport(0, 0, canvas.width, canvas.height);
    gl.clearColor(0.227, 0.239, 0.259, 1);  // the stage colour of the regular preview
    gl.enable(gl.DEPTH_TEST);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);

    const dist = size * 2.3 / zoom, yr = yaw * Math.PI / 180, pr = pitch * Math.PI / 180;
    const fx = -Math.cos(pr) * Math.cos(yr), fy = -Math.cos(pr) * Math.sin(yr), fz = -Math.sin(pr);
    const rl = Math.hypot(fx, fy), rx = fy / rl, ry = -fx / rl;
    const ux = ry * fz, uy = -rx * fz, uz = rx * fy - ry * fx;
    const c = [center[0] + rx * panR + ux * panU, center[1] + ry * panR + uy * panU, center[2] + uz * panU];
    mmPerPx = 2 * dist * Math.tan(17 * Math.PI / 180) / h;
    const eye = [c[0] - fx * dist, c[1] - fy * dist, c[2] - fz * dist];
    const mvp = mul(perspective(34, w / h, Math.max(1, dist / 60), dist * 3 + 800), lookAt(eye, c, [0, 0, 1]));
    gl.uniformMatrix4fv(U.uMvp, false, mvp);
    gl.uniform1f(U.uLayer, layer);
    gl.enableVertexAttribArray(aPos);

    gl.bindBuffer(gl.ARRAY_BUFFER, bedBuf);
    gl.vertexAttribPointer(aPos, 4, gl.FLOAT, false, 0, 0);
    gl.uniform1f(U.uPass, 0);
    gl.uniform1f(U.uFlat, 1);
    gl.uniform3f(U.uColor, 0.16, 0.17, 0.19);
    gl.drawArrays(gl.TRIANGLES, 0, 6);
    gl.uniform3f(U.uColor, 0.34, 0.36, 0.40);
    gl.drawArrays(gl.LINES, 6, bedLines);

    if (count) {
      gl.bindBuffer(gl.ARRAY_BUFFER, modelBuf);
      gl.vertexAttribPointer(aPos, 4, gl.FLOAT, false, 0, 0);
      gl.uniform1f(U.uFlat, 0);
      gl.drawArrays(gl.LINES, 0, count);
      gl.uniform1f(U.uPass, 1);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
      gl.depthMask(false);
      gl.drawArrays(gl.LINES, 0, count);
      gl.depthMask(true);
      gl.disable(gl.BLEND);
    }
  }
  function invalidate() {
    if (dirty) return;
    dirty = true;
    requestAnimationFrame(draw);
  }

  // mouse: drag turns, right button or shift drag moves, wheel zooms; touch: one finger turns, two zoom and move
  let drag = null;
  canvas.addEventListener("contextmenu", function (e) { e.preventDefault(); });
  canvas.addEventListener("pointerdown", function (e) {
    drag = { x: e.clientX, y: e.clientY, pan: e.button === 2 || e.shiftKey };
    canvas.setPointerCapture(e.pointerId);
  });
  canvas.addEventListener("pointermove", function (e) {
    if (!drag) return;
    const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
    drag.x = e.clientX; drag.y = e.clientY;
    if (drag.pan) { panR -= dx * mmPerPx; panU += dy * mmPerPx; }
    else { yaw -= dx * 0.4; pitch = Math.max(4, Math.min(88, pitch + dy * 0.4)); }
    invalidate();
  });
  canvas.addEventListener("pointerup", function () { drag = null; });
  canvas.addEventListener("pointercancel", function () { drag = null; });
  canvas.addEventListener("wheel", function (e) {
    e.preventDefault();
    zoom = Math.max(0.08, Math.min(8, zoom * Math.exp(-e.deltaY * 0.0015)));
    invalidate();
  }, { passive: false });
  canvas.addEventListener("dblclick", function () { yaw = -55; pitch = 28; zoom = 1; panR = panU = 0; invalidate(); });
  if (window.ResizeObserver) new ResizeObserver(invalidate).observe(canvas);
  dirty = false;
  invalidate();

  return {
    totalLayers: model.layers,
    segments: model.segments,
    setCurrentLayer: function (n) {
      n = Math.max(0, Math.min(model.layers, n));
      if (n === layer) return;
      layer = n;
      invalidate();
    },
  };
}

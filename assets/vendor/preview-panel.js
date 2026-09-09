// 3D print preview for the PrintHost dashboard: two GCodePreview instances
// (from the 'gcode-preview' npm package, vendored unbuilt as ES modules and
// served by DashboardRouter's /vendor/* route) stacked in one canvas pair —
// a translucent gray "ghost" of the whole model behind a solid, real-colored
// pass of just the printed-so-far layers. No controls of its own: the caller
// (dashboard.html) fetches GET /gcode/current once via initPrintPreview(),
// then calls the returned setCurrentLayer() whenever the existing /status
// poll loop sees currentLayer change — the same signal the old Layer X/Y
// pill used.
import { GCodePreview } from 'gcode-preview';
import * as THREE from 'three';
import { STLLoader } from './STLLoader.js';

const FILAMENT_COLOR = '#3ecf7e';
const GHOST_COLOR = '#9aa0ac';
const STAGE_BG = '#3a3d42';

// Absolute, not module-relative: these are used via fetch()/<img src> (STLLoader,
// Image), which resolve against the *page's* URL (dashboard.html, served at "/"),
// not against this module's own /vendor/ location the way an `import` specifier
// would. A relative './bed/...' here would silently resolve to /bed/... instead
// of /vendor/bed/... and 404.
const BED_MODEL_URL = '/vendor/bed/creality_ender3v3se_buildplate_model.stl';
const BED_TEXTURE_URL = '/vendor/bed/creality_ender3v3se_buildplate_texture.svg';
const BED_CENTER = { x: 110, y: 110 };
// Same iso direction as the library's own default camera angle, just pulled
// back ~30% farther so the bed sits comfortably inside the frame with margin
// on load instead of nearly filling it edge-to-edge. frameToFit() below
// immediately recomputes the real distance anyway; this is just where the
// camera starts before that first fit runs.
const INITIAL_CAMERA_POSITION = [-163, 520, 618];

export async function initPrintPreview({ ghostCanvas, solidCanvas, gcodeUrl, initialLayer }) {
  function prewarmAlphaContext(canvas) {
    try {
      canvas.getContext('webgl2', { alpha: true, antialias: true, preserveDrawingBuffer: false });
    } catch (e) {
      console.warn('Alpha context pre-warm failed, solid layer will render opaque:', e);
    }
  }
  prewarmAlphaContext(solidCanvas);

  const ghostPreview = new GCodePreview({
    canvas: ghostCanvas,
    backgroundColor: STAGE_BG,
    extrusionColor: GHOST_COLOR,
    renderTravel: false,
    renderTubes: true,
    initialCameraPosition: INITIAL_CAMERA_POSITION,
  });
  const solidPreview = new GCodePreview({
    canvas: solidCanvas,
    backgroundColor: STAGE_BG,
    extrusionColor: FILAMENT_COLOR,
    renderTravel: false,
    initialCameraPosition: INITIAL_CAMERA_POSITION,
    renderTubes: true,
  });
  // Empty space in the printed pass must show the ghost pass beneath it, not
  // a second opaque background. scene.background=null only stops it
  // overriding the clear color; the renderer's own clearAlpha still defaults
  // to 1 (opaque) regardless of the context's alpha:true from the pre-warm
  // above, so it has to be set explicitly too.
  solidPreview.sceneManager.scene.background = null;
  solidPreview.sceneManager.renderer.setClearAlpha(0);

  // Print bed, for spatial reference — lives in the GHOST scene, not solid's.
  // Solid's canvas is the transparent one (see setClearAlpha above): an
  // opaque bed sitting in that scene would paint over its own empty pixels,
  // exactly the region meant to stay see-through so the ghost model shows
  // through it. Living in the ghost scene instead, the bed gets normal
  // single-scene depth compositing with the ghost model and just rides along
  // with the ghost canvas's own CSS fade.
  //
  // Same rotation the library applies to its own extrusion/travel groups
  // (-90° about X) so a position given in raw G-code X/Y/Z lands in the
  // right spot. The STL's own local origin (0,0,0) belongs at the bed
  // shape's center — that convention comes straight from OrcaSlicer's own
  // renderer, which ships this exact model (Bed3D::update_model_offset in
  // its source: "move the model so that its origin goes into the bed shape
  // center"). BED_CENTER = 220x220 printable_area / 2, from OrcaSlicer's own
  // Ender-3 V3 SE machine profile.
  const bedGroup = new THREE.Group();
  bedGroup.quaternion.setFromEuler(new THREE.Euler(-Math.PI / 2, 0, 0));
  ghostPreview.sceneManager.scene.add(bedGroup);

  // The library's own extrusion/travel materials are unlit — shaded by
  // uniforms baked into their custom shader, not scene lights — so these
  // only light the bed mesh below; the ghost model's own appearance is
  // unaffected.
  ghostPreview.sceneManager.scene.add(new THREE.AmbientLight(0xffffff, 0.7));
  const bedLight = new THREE.DirectionalLight(0xffffff, 0.8);
  bedLight.position.set(0.3, 1, 0.5);
  ghostPreview.sceneManager.scene.add(bedLight);

  // The STL has no UV coordinates — it's plain CAD geometry with no texture
  // mapping baked in. OrcaSlicer doesn't texture that mesh either:
  // bed_texture is a separate decal (corner clips, warning icons), and even
  // that turns out to be just small corner marks — the checkered grid a
  // slicer actually shows comes from its own gridline renderer
  // (Bed3D::render_default's m_gridlines, dead/commented-out code in the
  // current OrcaSlicer source, nothing left there to pull). Draw an
  // equivalent grid ourselves instead — 10mm spacing, heavier every 50mm —
  // composited with the real corner-mark SVG onto one canvas, so the plate
  // reads as a measuring surface instead of a blank rectangle.
  function buildBedDecalCanvas(svgImage) {
    const pxPerMm = 4;
    const size = 220 * pxPerMm;
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext('2d');
    for (let mm = 0; mm <= 220; mm += 10) {
      const major = mm % 50 === 0;
      ctx.strokeStyle = major ? 'rgba(255,255,255,0.22)' : 'rgba(255,255,255,0.09)';
      ctx.lineWidth = major ? 1.5 : 1;
      const p = mm * pxPerMm;
      ctx.beginPath();
      ctx.moveTo(p, 0);
      ctx.lineTo(p, size);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(0, p);
      ctx.lineTo(size, p);
      ctx.stroke();
    }
    if (svgImage) ctx.drawImage(svgImage, 0, 0, size, size);
    return canvas;
  }

  new STLLoader().load(
    BED_MODEL_URL,
    (geometry) => {
      const bedMesh = new THREE.Mesh(
        geometry,
        new THREE.MeshStandardMaterial({ color: 0x2a2d32, roughness: 0.55, metalness: 0.15 }),
      );
      bedMesh.position.set(BED_CENTER.x, BED_CENTER.y, 0);
      bedGroup.add(bedMesh);
      ghostPreview.sceneManager.render();
    },
    undefined,
    // STLLoader silently no-ops on failure without this — the bed would just
    // never appear, with nothing in the console to explain why.
    (err) => console.error('Bed STL failed to load:', BED_MODEL_URL, err),
  );

  await new Promise((resolve) => {
    const svgImage = new Image();
    svgImage.onload = () => resolve(svgImage);
    svgImage.onerror = () => resolve(null);
    svgImage.src = BED_TEXTURE_URL;
  }).then((svgImage) => {
    const texture = new THREE.CanvasTexture(buildBedDecalCanvas(svgImage));
    texture.colorSpace = THREE.SRGBColorSpace;
    const plane = new THREE.Mesh(
      new THREE.PlaneGeometry(220, 220),
      new THREE.MeshBasicMaterial({ map: texture, transparent: true }),
    );
    plane.position.set(BED_CENTER.x, BED_CENTER.y, 0.02);
    bedGroup.add(plane);
    ghostPreview.sceneManager.render();
  });

  // Only the solid (top) canvas has live OrbitControls; the ghost camera is
  // driven from it every 'change' so both passes always show the same view.
  ghostPreview.sceneManager.controls.enabled = false;
  function syncGhostCamera() {
    const src = solidPreview.sceneManager.camera;
    const dst = ghostPreview.sceneManager.camera;
    dst.position.copy(src.position);
    dst.quaternion.copy(src.quaternion);
    dst.zoom = src.zoom;
    dst.updateProjectionMatrix();
    ghostPreview.sceneManager.controls.target.copy(solidPreview.sceneManager.controls.target);
  }
  solidPreview.sceneManager.controls.addEventListener('change', syncGhostCamera);

  // Panning intentionally disabled — rotate (drag) and zoom (wheel/pinch)
  // only. Panning moves `target` along with the camera, so a rotate right
  // after a pan would pivot around wherever panning left it instead of
  // staying centered on the bed, with no way back except a reload.
  solidPreview.sceneManager.controls.enablePan = false;

  // Frame on the real combined bounding box of bed + model rather than a
  // fixed distance: a fixed target at the bed's surface (z=0) looks wrong
  // for a tall model (camera looks at bed level while the model rises far
  // above it, so the bed sits low in frame with empty space above), and a
  // hand-tuned distance for one file doesn't generalize to others.
  // `frameBox` is computed once below, after parsing; frameToFit()
  // re-applies it against whatever the canvas size is *right now* each time
  // it's called — including from the ResizeObserver, because the very first
  // call can land before the browser has finished laying out the
  // (just-rendered) preview card, when clientWidth/clientHeight can still be
  // 0 or stale; nothing else would ever correct that afterward, since
  // resize() only touches camera.aspect, not the actual framing.
  let frameBox = null;
  function frameToFit() {
    if (!frameBox) return;
    const center = frameBox.getCenter(new THREE.Vector3());
    const size = frameBox.getSize(new THREE.Vector3());
    const camera = solidPreview.sceneManager.camera;
    const vFov = (camera.fov * Math.PI) / 180;
    const aspect = solidCanvas.clientWidth / solidCanvas.clientHeight || 4 / 3;
    const hFov = 2 * Math.atan(Math.tan(vFov / 2) * aspect);
    // Conservative: fit the box's full diagonal on both axes, so a tilted
    // iso view (which foreshortens no single axis to the frame edge) still
    // clears corner-to-corner instead of clipping a corner that axis-only
    // math wouldn't have accounted for.
    const diag = size.length();
    const distV = diag / 2 / Math.tan(vFov / 2);
    const distH = diag / 2 / Math.tan(hFov / 2);
    const margin = 1.15;
    const distance = Math.max(distV, distH) * margin;
    const direction = camera.position.clone().sub(solidPreview.sceneManager.controls.target).normalize();
    camera.position.copy(center).addScaledVector(direction, distance);
    solidPreview.sceneManager.controls.target.copy(center);
    solidPreview.sceneManager.controls.update();
    solidPreview.sceneManager.render();
    syncGhostCamera();
  }

  const stage = ghostCanvas.parentElement;
  new ResizeObserver(() => {
    solidPreview.sceneManager.resize();
    ghostPreview.sceneManager.resize();
    frameToFit();
  }).observe(stage);

  const res = await fetch(gcodeUrl);
  if (!res.ok) {
    throw new Error(`GET ${gcodeUrl} -> ${res.status} ${res.statusText}`);
  }
  const stream = () => res.clone().body.pipeThrough(new TextDecoderStream());
  // Feeding a real ReadableStream (rather than the whole text in one call)
  // matters: readStream() reads it in chunks with a plain `await
  // reader.read()` loop, so the parse naturally yields to the browser
  // between chunks instead of running as one long blocking call. render:
  // false matters just as much — with render left at its default,
  // processGCodeStream finishes by calling the *animated* renderer, which
  // draws over ~60 requestAnimationFrame steps; Chrome throttles rAF hard on
  // a tab that isn't the visible one (measured: turned parsing a real
  // 8.9MB/325-layer file into a multi-minute stall), and there's no reason
  // to want that growing-model animation anyway since layer visibility here
  // is driven by startLayer/endLayer. Render once, synchronously, ourselves.
  //
  // Each instance parses independently (the library keeps parsing coupled
  // to one job per GCodePreview) — an accepted cost, worth revisiting if it
  // proves too slow on very large files.
  await Promise.all([
    ghostPreview.processGCodeStream(stream(), { render: false }),
    solidPreview.processGCodeStream(stream(), { render: false }),
  ]);

  const totalLayers = solidPreview.countLayers;

  // The bed footprint is computed analytically (0,0 to 220,220 through
  // bedGroup's own transform) rather than by measuring the loaded STL mesh:
  // that load (above) is fire-and-forget, not awaited, so the mesh may well
  // not be in the scene yet at this point — Box3.setFromObject(scene) would
  // silently frame on the model alone whenever this runs first, shrinking
  // the bed footprint out of the shot instead of including it.
  bedGroup.updateMatrixWorld();
  const bedFootprint = new THREE.Box3(
    new THREE.Vector3(0, 0, 0),
    new THREE.Vector3(220, 220, 0),
  ).applyMatrix4(bedGroup.matrixWorld);
  const modelBox = new THREE.Box3().setFromObject(ghostPreview.sceneManager.objectsManager.extrusionsGroup);
  frameBox = bedFootprint.union(modelBox);
  frameToFit();

  ghostPreview.sceneManager.startLayer = 1;
  ghostPreview.sceneManager.endLayer = totalLayers;
  ghostPreview.sceneManager.render();

  let currentLayer = 0;
  function setCurrentLayer(n) {
    const clamped = Math.max(0, Math.min(totalLayers, n));
    if (clamped === currentLayer) return;
    currentLayer = clamped;
    // renderExtrusion, not canvas visibility — the bed model lives in the
    // ghost scene and must stay visible even at layer 0, when nothing's
    // printed yet to show in the solid pass.
    if (currentLayer <= 0) {
      solidPreview.sceneManager.renderExtrusion = false;
    } else {
      solidPreview.sceneManager.renderExtrusion = true;
      solidPreview.sceneManager.startLayer = 1;
      solidPreview.sceneManager.endLayer = currentLayer;
    }
    solidPreview.sceneManager.render();
  }
  setCurrentLayer(initialLayer || 0);
  syncGhostCamera();

  return { totalLayers, setCurrentLayer };
}

// Smooth, rigged, outfit-able avatars for three.js.
//
// Each body is ONE continuous surface: rounded body parts are blended with a
// smooth-union signed distance field and polygonised with marching cubes, so
// neck, shoulders, elbows, hips and knees flow into each other instead of
// looking like separate balls and tubes. The surface is then skinned to a
// skeleton (smooth distance-based weights) so joints bend like a real body.
//
// Clothes are painted in the shader from the rest-pose position (so borders
// and woven tilet patterns stay crisp), plus optional cloth shells (dress,
// tunic, cape) skinned to the same skeleton.
import * as THREE from 'three';
import { MarchingCubes } from 'three/addons/objects/MarchingCubes.js';

// ---------------------------------------------------------------- outfits --
// Re-usable outfit presets, in the spirit of a stylised avatar outfit sheet.
// colours: top, bottom ('skin' = bare legs), shoe; tilet = woven border colours [motif, base, edge]
export const OUTFITS = {
  habesha_kemis: {            // Ethiopian woman's dress: white cotton, woven tilet borders
    top: '#f6f1e6', bottom: 'skin', shoe: '#c79a3e',
    tilet: ['#c2185b', '#f6f1e6', '#d4a017'],
    strip: .034, neckBand: .05, cuff: [.69, .755],
    dress: { top: 1.04, hem: .1, profile: [[1.04, .118], [.92, .188], [.7, .225], [.4, .28], [.1, .335]], bands: [[.12, .29], [.33, .37]] },
  },
  habesha_libs: {             // Ethiopian man's outfit: long white shirt with tilet trim, white trousers
    top: '#f6f1e6', bottom: '#ece5d6', shoe: '#5e3a22',
    tilet: ['#16824a', '#f6f1e6', '#e0b020'],
    strip: .03, neckBand: .045, cuff: [.69, .75],
    dress: { top: 1.02, hem: .6, profile: [[1.02, .142], [.92, .178], [.75, .2], [.6, .212]], bands: [[.615, .7]] },
    sash: '#16824a',
  },
  orange_jacket_jeans: { top: '#e07a2e', bottom: '#3b5a86', shoe: '#5b4636', collar: '#2f2f33' },
  chef_whites:        { top: '#f4f4f2', bottom: '#5d7fa6', shoe: '#2d2d2d', strip: .03, tilet: ['#2d2d2d', '#f4f4f2', '#f4f4f2'] },
  red_overalls:       { top: '#f2f2f2', bottom: '#d9574f', shoe: '#3a3a3a', bib: '#d9574f' },
  teal_tee_red_shorts:{ top: '#38b2a6', bottom: '#d64545', shoe: '#2b6fb3', shorts: .62 },
};

// ------------------------------------------------------------- body types --
export const BODIES = {
  man:   { s: 1.0,  sh: .185, hip: .098, chest: .158, waist: .132, pelvis: .152, arm: [.052, .044, .037, .031], leg: [.084, .061, .052, .038], bust: 0 },
  woman: { s: .935, sh: .165, hip: .1,   chest: .142, waist: .112, pelvis: .162, arm: [.044, .037, .032, .027], leg: [.084, .058, .048, .034], bust: 1 },
};

// ------------------------------------------------------------- SDF helpers --
function sdRoundCone(px, py, pz, a, b, r1, r2) {
  const bax = b[0] - a[0], bay = b[1] - a[1], baz = b[2] - a[2];
  const l2 = bax * bax + bay * bay + baz * baz, rr = r1 - r2, a2 = l2 - rr * rr, il2 = 1 / l2;
  const pax = px - a[0], pay = py - a[1], paz = pz - a[2];
  const y = pax * bax + pay * bay + paz * baz, z = y - l2;
  const xx = pax * l2 - bax * y, xy = pay * l2 - bay * y, xz = paz * l2 - baz * y;
  const x2 = xx * xx + xy * xy + xz * xz, y2 = y * y * l2, z2 = z * z * l2;
  const k = Math.sign(rr) * rr * rr * x2;
  if (Math.sign(z) * a2 * z2 > k) return Math.sqrt(x2 + z2) * il2 - r2;
  if (Math.sign(y) * a2 * y2 < k) return Math.sqrt(x2 + y2) * il2 - r1;
  return (Math.sqrt(x2 * a2 * il2) + y * rr) * il2 - r1;
}
function sdEllipsoid(px, py, pz, c, r) {
  const x = (px - c[0]) / r[0], y = (py - c[1]) / r[1], z = (pz - c[2]) / r[2];
  const k0 = Math.sqrt(x * x + y * y + z * z);
  const x1 = x / r[0], y1 = y / r[1], z1 = z / r[2];
  const k1 = Math.sqrt(x1 * x1 + y1 * y1 + z1 * z1);
  return k1 < 1e-9 ? -Math.min(...r) : k0 * (k0 - 1) / k1;
}
const smin = (a, b, k) => { const h = Math.max(k - Math.abs(a - b), 0) / k; return Math.min(a, b) - h * h * k * .25; };

// ------------------------------------------------------------- build rig --
function layout(B) {
  const s = B.s, S = v => v.map(x => x * s);
  const J = {};
  J.hips = S([0, .95, 0]); J.spine = S([0, 1.08, 0]); J.chest = S([0, 1.26, 0]); J.neck = S([0, 1.455, 0]);
  J.head = S([0, 1.575, 0]); J.headTop = S([0, 1.8, .01]);
  const A = 50 * Math.PI / 180, lens = [.29, .26, .17].map(l => l * s);
  for (const [side, n] of [[1, 'L'], [-1, 'R']]) {
    J['clav' + n] = S([side * .02, 1.42, -.005]);
    const sh = [side * B.sh * s, 1.42 * s, -.005 * s]; J['uArm' + n] = sh;
    const d = [Math.sin(A) * side, -Math.cos(A), 0];
    J['fArm' + n] = sh.map((v, i) => v + d[i] * lens[0]);
    J['hand' + n] = J['fArm' + n].map((v, i) => v + d[i] * lens[1]);
    J['tip' + n] = J['hand' + n].map((v, i) => v + d[i] * lens[2]);
    J['thigh' + n] = S([side * B.hip, .92, 0]); J['shin' + n] = S([side * B.hip, .505, .012]);
    J['foot' + n] = S([side * B.hip, .088, -.012]); J['toe' + n] = S([side * B.hip * 1.05, .03, .16]);
  }
  return { J, armDir: A, armLens: lens };
}

function primitives(B, J) {
  const s = B.s, S = v => v.map(x => x * s), P = [];
  const cone = (a, b, r1, r2, k = .028, zs = 1) => P.push({ t: 'c', a, b, r1: r1 * s, r2: r2 * s, k: k * s, zs });
  const ell = (c, r, k = .028) => P.push({ t: 'e', c, r: r.map(x => x * s), k: k * s });
  // torso
  ell(S([0, .93, -.005]), [B.pelvis / s + .015, .105, B.pelvis / s * .82], .035);
  cone(S([0, .95, 0]), S([0, 1.17, 0]), B.waist / s, B.waist / s + .012, .05, 1.32);
  cone(S([0, 1.15, 0]), S([0, 1.34, -.005]), B.chest / s - .012, B.chest / s, .05, 1.38);
  cone(S([-(B.sh / s) + .035, 1.395, -.01]), S([B.sh / s - .035, 1.395, -.01]), .068, .068, .05, 1.25);
  if (B.bust) for (const x of [-.058, .058]) ell(S([x, 1.29, .085]), [.058, .056, .05], .04);
  // neck & head
  cone(S([0, 1.37, -.01]), S([0, 1.6, .005]), .056, .05, .045);
  ell(S([0, 1.69, .006]), [.091, .114, .104], .03);
  cone(S([0, 1.655, .025]), S([0, 1.615, .062]), .07, .043, .04);
  cone(S([0, 1.716, .099]), S([0, 1.672, .117]), .009, .019, .012);           // nose
  for (const x of [-.091, .091]) ell(S([x, 1.688, -.003]), [.016, .03, .022], .012); // ears
  // limbs
  for (const n of ['L', 'R']) {
    const side = n === 'L' ? 1 : -1;
    ell(J['uArm' + n].map((v, i) => v + [side * -.005 * s, -.01 * s, 0][i]), [.066, .066, .06].map(r => r * B.arm[0] / .052), .045);
    cone(J['uArm' + n], J['fArm' + n], B.arm[0] / s, B.arm[1] / s, .03);
    cone(J['fArm' + n], J['hand' + n], B.arm[1] / s * 1.04, B.arm[2] / s, .03);
    const h = J['hand' + n], tp = J['tip' + n], mid = h.map((v, i) => v + (tp[i] - v) * .45);
    cone(h, mid, B.arm[2] / s * .95, B.arm[3] / s * 1.18, .022);
    cone(mid, tp, B.arm[3] / s * 1.18, B.arm[3] / s * .72, .02);
    cone(h.map((v, i) => v + (tp[i] - v) * .12 + [0, 0, .022 * s][i]), h.map((v, i) => v + (tp[i] - v) * .42 + [0, 0, .045 * s][i]), .014, .011, .016); // thumb
    cone(J['thigh' + n].map((v, i) => i === 1 ? v + .01 * s : v), J['shin' + n], B.leg[0] / s, B.leg[1] / s, .03);
    ell(J['shin' + n].map((v, i) => v + [0, -.13 * s, -.022 * s][i]), [B.leg[1] / s * .95, .11, B.leg[1] / s * 1.0], .05); // calf
    cone(J['shin' + n], J['foot' + n], B.leg[2] / s, B.leg[3] / s, .03);
    const f = J['foot' + n], toe = J['toe' + n];
    cone([f[0], .048 * s, -.045 * s], [toe[0], .036 * s, toe[2]], .046, .036, .03);
    cone([f[0], .07 * s, -.03 * s], f, .045, .04, .03);
  }
  // bounding spheres for fast rejection
  for (const p of P) {
    if (p.t === 'c') { p.cx = p.a.map((v, i) => (v + p.b[i]) / 2); p.br = Math.hypot(...p.a.map((v, i) => v - p.b[i])) / 2 + Math.max(p.r1, p.r2) * Math.max(1, 1) ; }
    else { p.cx = p.c; p.br = Math.max(...p.r); }
  }
  return P;
}
function makeSDF(P) {
  return (x, y, z) => {
    let d = 1e9;
    for (let i = 0; i < P.length; i++) {
      const p = P[i];
      const dx = x - p.cx[0], dy = y - p.cx[1], dz = z - p.cx[2];
      if (Math.sqrt(dx * dx + dy * dy + dz * dz) - p.br > d + p.k) continue;
      let v;
      if (p.t === 'c') {
        if (p.zs !== 1) { const cz = p.cx[2]; v = sdRoundCone(x, y, cz + (z - cz) * p.zs, p.a, p.b, p.r1, p.r2) / p.zs * 1.0; }
        else v = sdRoundCone(x, y, z, p.a, p.b, p.r1, p.r2);
      } else v = sdEllipsoid(x, y, z, p.c, p.r);
      d = d > 1e8 ? v : smin(d, v, p.k);
    }
    return d;
  };
}

// ------------------------------------------------------------- shaders --
const TILET_GLSL = /* glsl */`
uniform vec3 uTA; uniform vec3 uTB; uniform vec3 uTC;
vec3 tilet(float u, float v) {
  vec3 c = uTB;
  if (v < .13 || v > .87) return uTC;
  if (v < .2 || v > .8) return uTA;
  float z = abs(fract(u) - .5) * 2.;
  if (abs(v - (.28 + .44 * z)) < .085) c = uTA;
  float dm = abs(fract(u + .5) - .5) * 1.4 + abs(v - .5);
  if (dm < .13) c = uTC;
  if (dm < .055) c = uTA;
  return c;
}
float weave(vec3 p) { return .975 + .025 * sin(p.x * 520.) * sin(p.y * 520. + p.z * 300.); }
`;
function patchMaterial(mat, uniforms, colorCode) {
  mat.onBeforeCompile = sh => {
    Object.assign(sh.uniforms, uniforms);
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\nattribute vec3 aRest; attribute float aArm; varying vec3 vRest; varying float vArm;')
      .replace('#include <begin_vertex>', '#include <begin_vertex>\nvRest = aRest; vArm = aArm;');
    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>', '#include <common>\nvarying vec3 vRest; varying float vArm;\n' + TILET_GLSL + colorCode.decl)
      .replace('vec4 diffuseColor = vec4( diffuse, opacity );', 'vec4 diffuseColor = vec4( diffuse, opacity );\n' + colorCode.body);
  };
  return mat;
}
const C = c => new THREE.Color(c);

// ------------------------------------------------------------- avatar --
export function buildAvatar({ body = 'man', outfit = 'habesha_libs', skin = '#8a5638', hair = 'short', res = 150, eyes = '#2b1a10' } = {}) {
  const B = BODIES[body], O = OUTFITS[outfit], s = B.s;
  const { J, armLens } = layout(B);
  const P = primitives(B, J), sdf = makeSDF(P);

  // ---- polygonise with marching cubes
  const ctr = [0, .92 * s, 0], half = 1.0 * s;
  const mc = new MarchingCubes(res, new THREE.MeshBasicMaterial(), false, false, 400000);
  mc.isolation = 0;
  const size = mc.size, hs = mc.halfsize;
  let bx = [1e9, -1e9], by = [1e9, -1e9], bz = [1e9, -1e9];
  for (const p of P) { bx = [Math.min(bx[0], p.cx[0] - p.br), Math.max(bx[1], p.cx[0] + p.br)]; by = [Math.min(by[0], p.cx[1] - p.br), Math.max(by[1], p.cx[1] + p.br)]; bz = [Math.min(bz[0], p.cx[2] - p.br), Math.max(bz[1], p.cx[2] + p.br)]; }
  for (let z = 0; z < size; z++) {
    const wz = ctr[2] + (z - hs) / hs * half;
    for (let y = 0; y < size; y++) {
      const wy = ctr[1] + (y - hs) / hs * half;
      for (let x = 0; x < size; x++) {
        const wx = ctr[0] + (x - hs) / hs * half;
        const q = x + y * size + z * size * size;
        if (wx < bx[0] || wx > bx[1] || wy < by[0] || wy > by[1] || wz < bz[0] || wz > bz[1]) { mc.field[q] = -1; continue; }
        mc.field[q] = -sdf(wx, wy, wz);
      }
    }
  }
  mc.update();
  const vcount = mc.count;
  const pos = new Float32Array(vcount * 3);
  for (let i = 0; i < vcount * 3; i++) pos[i] = ctr[i % 3] + mc.positionArray[i] * half;
  mc.geometry.dispose();

  // normals from the SDF gradient; fix winding to match
  const nor = new Float32Array(vcount * 3), e = .0015;
  for (let i = 0; i < vcount; i++) {
    const x = pos[i * 3], y = pos[i * 3 + 1], z = pos[i * 3 + 2];
    let nx = sdf(x + e, y, z) - sdf(x - e, y, z), ny = sdf(x, y + e, z) - sdf(x, y - e, z), nz = sdf(x, y, z + e) - sdf(x, y, z - e);
    const l = Math.hypot(nx, ny, nz) || 1; nor[i * 3] = nx / l; nor[i * 3 + 1] = ny / l; nor[i * 3 + 2] = nz / l;
  }
  {
    let agree = 0;
    for (let t = 0; t < vcount; t += 3 * 50) {
      const a = t * 3, b = a + 3, c = a + 6;
      const ux = pos[b] - pos[a], uy = pos[b + 1] - pos[a + 1], uz = pos[b + 2] - pos[a + 2];
      const vx = pos[c] - pos[a], vy = pos[c + 1] - pos[a + 1], vz = pos[c + 2] - pos[a + 2];
      const cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
      agree += Math.sign(cx * nor[a] + cy * nor[a + 1] + cz * nor[a + 2]);
    }
    if (agree < 0) for (let t = 0; t < vcount; t += 3) for (let k = 0; k < 3; k++) {
      const a = (t + 1) * 3 + k, b = (t + 2) * 3 + k;
      [pos[a], pos[b]] = [pos[b], pos[a]]; [nor[a], nor[b]] = [nor[b], nor[a]];
    }
  }

  // ---- skeleton
  const names = ['hips', 'spine', 'chest', 'neck', 'head', 'clavL', 'uArmL', 'fArmL', 'handL', 'clavR', 'uArmR', 'fArmR', 'handR',
    'thighL', 'shinL', 'footL', 'thighR', 'shinR', 'footR'];
  const parent = { spine: 'hips', chest: 'spine', neck: 'chest', head: 'neck', clavL: 'chest', uArmL: 'clavL', fArmL: 'uArmL', handL: 'fArmL',
    clavR: 'chest', uArmR: 'clavR', fArmR: 'uArmR', handR: 'fArmR', thighL: 'hips', shinL: 'thighL', footL: 'shinL', thighR: 'hips', shinR: 'thighR', footR: 'shinR' };
  const tail = { hips: J.spine, spine: J.chest, chest: J.neck, neck: J.head, head: J.headTop, clavL: J.uArmL, uArmL: J.fArmL, fArmL: J.handL, handL: J.tipL,
    clavR: J.uArmR, uArmR: J.fArmR, fArmR: J.handR, handR: J.tipR, thighL: J.shinL, shinL: J.footL, footL: J.toeL, thighR: J.shinR, shinR: J.footR, footR: J.toeR };
  const bones = {}, list = [];
  for (const n of names) {
    const b = new THREE.Bone(); b.name = n; bones[n] = b; list.push(b);
    const p = J[n], pp = parent[n] ? J[parent[n]] : [0, 0, 0];
    b.position.set(p[0] - pp[0], p[1] - pp[1], p[2] - pp[2]);
    if (parent[n]) bones[parent[n]].add(b);
  }
  const children = {}; names.forEach(n => { if (parent[n]) (children[parent[n]] ||= []).push(n); });

  // ---- skin weights (smooth, restricted to the nearest bone's neighbours so legs never pull on each other)
  const segs = names.map(n => ({ n, a: J[n], b: tail[n] }));
  const segDist = (sg, x, y, z) => {
    const ax = sg.b[0] - sg.a[0], ay = sg.b[1] - sg.a[1], az = sg.b[2] - sg.a[2];
    const t = clamp01(((x - sg.a[0]) * ax + (y - sg.a[1]) * ay + (z - sg.a[2]) * az) / (ax * ax + ay * ay + az * az));
    return [Math.hypot(x - sg.a[0] - ax * t, y - sg.a[1] - ay * t, z - sg.a[2] - az * t), t];
  };
  const idx = Object.fromEntries(names.map((n, i) => [n, i]));
  const armChain = { uArm: 0, fArm: armLens[0], hand: armLens[0] + armLens[1] }, armTotal = armLens[0] + armLens[1] + armLens[2];
  function weightsFor(x, y, z) {
    let best = 1e9, bi = 0, bt = 0; const ds = [];
    segs.forEach((sg, i) => { const [d, t] = segDist(sg, x, y, z); ds.push(d); if (d < best) { best = d; bi = i; bt = t; } });
    const pn = segs[bi].n, allowed = new Set([pn, parent[pn], ...(children[pn] || [])].filter(Boolean));
    if (pn === 'hips') { allowed.add('spine'); }
    if (['chest', 'spine', 'neck', 'hips', 'clavL', 'clavR'].includes(pn)) { allowed.delete('uArmL'); allowed.delete('uArmR'); }
    if (pn.startsWith('uArm')) allowed.add('chest');
    let ws = [...allowed].map(n => [idx[n], Math.pow(1 / Math.max(ds[idx[n]], .004), 6)]);
    ws.sort((a, b) => b[1] - a[1]); ws = ws.slice(0, 4);
    const sum = ws.reduce((a, w) => a + w[1], 0);
    let arm = -1; const base = pn.replace(/[LR]$/, '');
    if (base in armChain) arm = (armChain[base] + bt * (base === 'hand' ? armLens[2] : base === 'fArm' ? armLens[1] : armLens[0])) / armTotal;
    return { ws: ws.map(([i, w]) => [i, w / sum]), arm };
  }
  const skinIndex = new Uint16Array(vcount * 4), skinWeight = new Float32Array(vcount * 4), aArm = new Float32Array(vcount);
  for (let i = 0; i < vcount; i++) {
    const { ws, arm } = weightsFor(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]);
    ws.forEach(([bi, w], k) => { skinIndex[i * 4 + k] = bi; skinWeight[i * 4 + k] = w; });
    aArm[i] = arm;
  }
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  geo.setAttribute('normal', new THREE.BufferAttribute(nor, 3));
  geo.setAttribute('aRest', new THREE.BufferAttribute(pos.slice(), 3));
  geo.setAttribute('aArm', new THREE.BufferAttribute(aArm, 1));
  geo.setAttribute('skinIndex', new THREE.Uint16BufferAttribute(skinIndex, 4));
  geo.setAttribute('skinWeight', new THREE.BufferAttribute(skinWeight, 4));

  // ---- painted outfit
  const tilet = O.tilet || [O.top, O.top, O.top];
  const U = {
    uSkin: { value: C(skin) }, uTop: { value: C(O.top) }, uBottom: { value: C(O.bottom === 'skin' ? (O.dress ? O.top : skin) : O.bottom) }, uShoe: { value: C(O.shoe) },
    uTA: { value: C(tilet[0]) }, uTB: { value: C(tilet[1]) }, uTC: { value: C(tilet[2]) },
    uWaist: { value: (O.shorts ? O.shorts : .965) * s }, uNeckY: { value: 1.452 * s }, uShoeY: { value: .115 * s }, uHandT: { value: (armLens[0] + armLens[1]) / armTotal },
    uStrip: { value: (O.strip || 0) * s }, uNeckBand: { value: (O.neckBand || 0) * s }, uCuff: { value: new THREE.Vector2(...(O.cuff || [2, 2])) },
    uBib: { value: C(O.bib || O.top) }, uHasBib: { value: O.bib ? 1 : 0 }, uCollar: { value: C(O.collar || O.top) }, uHasCollar: { value: O.collar ? 1 : 0 },
    uBareLegs: { value: O.bottom === 'skin' || O.shorts ? 1 : 0 }, uS: { value: s }, uBareY: { value: (O.dress ? O.dress.hem + .06 : (O.shorts || .965) - .06) * s },
  };
  const bodyMat = patchMaterial(new THREE.MeshStandardMaterial({ roughness: .78, metalness: 0 }), U, {
    decl: `uniform vec3 uSkin, uTop, uBottom, uShoe, uBib, uCollar; uniform float uWaist, uNeckY, uShoeY, uHandT, uStrip, uNeckBand, uHasBib, uHasCollar, uBareLegs, uS, uBareY; uniform vec2 uCuff;`,
    body: `
      vec3 col; float y = vRest.y; bool torso = vArm < 0.;
      bool cloth = true;
      if (vArm > uHandT) { col = uSkin; cloth = false; }
      else if (torso && (y > 1.5 * uS || (y > uNeckY - .03 * uS && length(vec2(vRest.x, vRest.z * 1.15)) < .078 * uS))) { col = uSkin; cloth = false; }
      else if (y < uShoeY) { col = uShoe; cloth = false; }
      else if (torso && y < uWaist) {
        col = uBottom;
        if (uBareLegs > .5 && y < uBareY) { col = uSkin; cloth = false; }
      } else {
        col = uTop;
        if (torso && uHasBib > .5 && vRest.z > 0. && abs(vRest.x) < .1 * uS && y < 1.3 * uS) col = uBib;
        if (torso && uHasCollar > .5 && y > uNeckY - .04 * uS) col = uCollar;
        float nr = length(vec2(vRest.x, vRest.z * 1.15));
        if (torso && uNeckBand > 0. && y > 1.3 * uS && nr < .078 * uS + uNeckBand && nr > .07 * uS) col = tilet(atan(vRest.x, vRest.z) * 7., (nr - .07 * uS) / (uNeckBand + .008 * uS));
        if (torso && uStrip > 0. && abs(vRest.x) < uStrip && vRest.z > 0. && y > 1.02 * uS) col = tilet(y * 26., (vRest.x + uStrip) / (2. * uStrip));
        if (!torso && vArm > uCuff.x && vArm < uCuff.y) col = tilet((vRest.y + vRest.z) * 40., (vArm - uCuff.x) / (uCuff.y - uCuff.x));
      }
      if (cloth) col *= weave(vRest);
      diffuseColor.rgb = col;`,
  });
  const mesh = new THREE.SkinnedMesh(geo, bodyMat);
  mesh.castShadow = mesh.receiveShadow = true; mesh.frustumCulled = false;

  const group = new THREE.Group();
  group.add(bones.hips); group.add(mesh);
  group.updateMatrixWorld(true);
  const skeleton = new THREE.Skeleton(list);
  mesh.bind(skeleton);
  const skinned = [mesh];

  // ---- cloth shell (dress / tunic), skinned: hips at the waist, thighs towards the hem
  if (O.dress) {
    const D = O.dress, prof = D.profile.map(([y, r]) => [y * s, r * s]);
    const pts = [];
    for (let i = 0; i <= 40; i++) {
      const y = lerp(prof[0][0], prof[prof.length - 1][0], i / 40);
      let k = 0; while (k < prof.length - 2 && y < prof[k + 1][0]) k++;
      const [y0, r0] = prof[k], [y1, r1] = prof[k + 1], u = (y0 - y) / (y0 - y1);
      pts.push(new THREE.Vector2(lerp(r0, r1, u * u * (3 - 2 * u) * .5 + u * .5), y));
    }
    pts.reverse();
    const dg = new THREE.LatheGeometry(pts, 96);
    dg.scale(1, 1, .86);
    const dp = dg.attributes.position, n = dp.count;
    const si = new Uint16Array(n * 4), sw = new Float32Array(n * 4);
    for (let i = 0; i < n; i++) {
      const x = dp.getX(i), y = dp.getY(i);
      const t = clamp01((D.top * s - y) / ((D.top - D.hem) * s)), side = smooth01((x / s + .09) / .18);
      const leg = .78 * smooth01(t * 1.3);
      si.set([idx.hips, idx.thighL, idx.thighR, idx.spine], i * 4);
      sw.set([1 - leg, leg * side, leg * (1 - side), 0], i * 4);
    }
    dg.setAttribute('skinIndex', new THREE.Uint16BufferAttribute(si, 4));
    dg.setAttribute('skinWeight', new THREE.BufferAttribute(sw, 4));
    dg.setAttribute('aRest', new THREE.BufferAttribute(dp.array.slice(), 3));
    dg.setAttribute('aArm', new THREE.BufferAttribute(new Float32Array(n).fill(-1), 1));
    const bands = (D.bands || []).map(b => new THREE.Vector2(b[0] * s, b[1] * s));
    while (bands.length < 2) bands.push(new THREE.Vector2(-1, -1));
    const dm = patchMaterial(new THREE.MeshStandardMaterial({ roughness: .82, side: THREE.DoubleSide }),
      { ...U, uB0: { value: bands[0] }, uB1: { value: bands[1] }, uSash: { value: C(O.sash || O.top) }, uHasSash: { value: O.sash ? 1 : 0 } }, {
        decl: `uniform vec3 uTop, uSash; uniform vec2 uB0, uB1; uniform float uStrip, uHasSash, uS;`,
        body: `
          vec3 col = uTop; float y = vRest.y; float ang = atan(vRest.x, vRest.z);
          if (y > uB0.x && y < uB0.y) col = tilet(ang * 9. / 3.14159 * 2., (y - uB0.x) / (uB0.y - uB0.x));
          if (y > uB1.x && y < uB1.y) col = tilet(ang * 18. / 3.14159 * 2., (y - uB1.x) / (uB1.y - uB1.x));
          if (uStrip > 0. && abs(vRest.x) < uStrip && vRest.z > 0. && y > uB0.y) col = tilet(y * 26., (vRest.x + uStrip) / (2. * uStrip));
          if (uHasSash > .5 && y > (1.02 - .06) * uS) col = uSash;
          if (!gl_FrontFacing) col *= .7;
          diffuseColor.rgb = col * weave(vRest);`,
      });
    const dress = new THREE.SkinnedMesh(dg, dm);
    dress.castShadow = dress.receiveShadow = true; dress.frustumCulled = false;
    group.add(dress); dress.bind(skeleton, mesh.bindMatrix); skinned.push(dress);
  }

  // ---- face & hair (children of the head bone)
  const head = bones.head, hp = J.head;
  const surfZ = (x, y) => { let a = 0, b = .3 * s; for (let i = 0; i < 30; i++) { const m = (a + b) / 2; if (sdf(x, y, m) < 0) a = m; else b = m; } return a; };
  const local = (x, y, z) => new THREE.Vector3(x - hp[0], y - hp[1], z - hp[2]);
  const mat = (c, r = .6) => new THREE.MeshStandardMaterial({ color: c, roughness: r });
  const skinM = mat(skin, .7), hairM = mat('#15100c', .95);
  const face = { eyes: [] };
  for (const sx of [-1, 1]) {
    const ex = sx * .036 * s, ey = 1.707 * s, ez = surfZ(ex, ey) - .012 * s;
    const eg = new THREE.Group(); eg.position.copy(local(ex, ey, ez)); eg.scale.set(1, 1, .62);
    const white = new THREE.Mesh(new THREE.SphereGeometry(.021 * s, 20, 14), mat('#fbf8f2', .35)); eg.add(white);
    const iris = new THREE.Mesh(new THREE.SphereGeometry(.0125 * s, 16, 12), mat(eyes, .3)); iris.position.z = .013 * s; eg.add(iris);
    const pupil = new THREE.Mesh(new THREE.SphereGeometry(.006 * s, 10, 8), mat('#050302', .2)); pupil.position.z = .0205 * s; eg.add(pupil);
    const glint = new THREE.Mesh(new THREE.SphereGeometry(.0028 * s, 8, 6), new THREE.MeshBasicMaterial({ color: '#ffffff' })); glint.position.set(.004 * s, .005 * s, .023 * s); eg.add(glint);
    const lid = new THREE.Mesh(new THREE.SphereGeometry(.0235 * s, 20, 10, 0, Math.PI * 2, 0, Math.PI / 2), skinM); eg.add(lid);
    const lash = new THREE.Mesh(new THREE.TorusGeometry(.0225 * s, .0025 * s, 6, 20, Math.PI), mat('#120c08', .8)); lash.rotation.x = -.15; eg.add(lash);
    head.add(eg); face.eyes.push({ lid, iris, pupil, eg });
    const by = 1.742 * s, bzz = surfZ(sx * .038 * s, by);
    const brow = new THREE.Mesh(new THREE.CapsuleGeometry(.0055 * s, .03 * s, 4, 8), hairM);
    brow.rotation.z = Math.PI / 2 + sx * .12; brow.position.copy(local(sx * .038 * s, by, bzz + .001)); head.add(brow);
  }
  // lips & mouth: a soft smile curve, opening into a laugh with a row of teeth
  {
    const my = 1.646 * s, mz = surfZ(0, my);
    const mouth = new THREE.Group(); mouth.position.copy(local(0, my, mz - .004 * s)); head.add(mouth);
    const lipM = mat(new THREE.Color(skin).multiplyScalar(.6).getStyle(), .5);
    const inner = new THREE.Mesh(new THREE.CircleGeometry(.019 * s, 28, Math.PI, Math.PI), mat('#3b1510', .9));
    inner.position.set(0, .002 * s, .0035 * s); mouth.add(inner);
    const teeth = new THREE.Mesh(new THREE.PlaneGeometry(.03 * s, .0055 * s), mat('#f5f2ea', .4));
    teeth.position.set(0, -.0008 * s, .004 * s); mouth.add(teeth);
    const lower = new THREE.Mesh(new THREE.TorusGeometry(.019 * s, .0052 * s, 8, 28, Math.PI), lipM);
    lower.rotation.z = Math.PI; lower.scale.y = .55; mouth.add(lower);
    const upper = new THREE.Mesh(new THREE.TorusGeometry(.021 * s, .0042 * s, 8, 28, Math.PI), lipM);
    upper.rotation.z = Math.PI; upper.scale.y = .18; upper.position.y = .0035 * s; mouth.add(upper);
    face.mouth = { mouth, upper, lower, inner, teeth };
  }
  // hair
  const hc = [0, 1.69 * s, .006 * s], hr = [.091 * s, .114 * s, .104 * s];
  if (hair === 'braids') {
    const cap = new THREE.Mesh(new THREE.SphereGeometry(1, 48, 24, 0, Math.PI * 2, 0, Math.PI * .5), hairM);
    cap.scale.set(hr[0] * 1.06, hr[1] * 1.07, hr[2] * 1.07); cap.rotation.x = -.42; cap.position.copy(local(...hc)); head.add(cap);
    for (let i = -4; i <= 4; i++) { // cornrow braids (shuruba) running back over the head
      const tg = new THREE.TorusGeometry(1, .07, 6, 40, Math.PI * .95);
      const m = new THREE.Mesh(tg, hairM); m.rotation.set(0, Math.PI / 2, 0);
      const g = new THREE.Group(); g.add(m); g.scale.set(hr[0] * 1.09, hr[1] * 1.08, hr[2] * 1.08);
      const holder = new THREE.Group(); holder.add(g); holder.rotation.set(-.42 + Math.PI * .02, 0, i * .17);
      holder.position.copy(local(...hc)); head.add(holder);
    }
    const bunM = hairM;
    for (let i = 0; i < 7; i++) {
      const a = i / 7 * Math.PI * 2, r = .035 * s;
      const b = new THREE.Mesh(new THREE.SphereGeometry(.03 * s, 14, 10), bunM);
      b.position.copy(local(Math.cos(a) * r, 1.79 * s + Math.sin(a) * r * .6, -.07 * s + Math.sin(a) * .01)); head.add(b);
    }
    const bun = new THREE.Mesh(new THREE.SphereGeometry(.045 * s, 20, 14), bunM); bun.position.copy(local(0, 1.79 * s, -.075 * s)); head.add(bun);
    for (const sx of [-1, 1]) { // small gold earrings
      const er = new THREE.Mesh(new THREE.TorusGeometry(.011 * s, .0025 * s, 8, 20), mat('#d8ab3a', .3));
      er.position.copy(local(sx * .094 * s, 1.655 * s, .0)); er.rotation.y = Math.PI / 2; head.add(er);
    }
  } else {
    const capG = new THREE.SphereGeometry(1, 64, 32, 0, Math.PI * 2, 0, Math.PI * .52);
    const cp = capG.attributes.position; // short curly texture
    for (let i = 0; i < cp.count; i++) { const x = cp.getX(i), y = cp.getY(i), z = cp.getZ(i); const n = 1 + .025 * Math.sin(x * 40) * Math.sin(y * 37) * Math.sin(z * 43); cp.setXYZ(i, x * n, y * n, z * n); }
    capG.computeVertexNormals();
    const cap = new THREE.Mesh(capG, hairM); cap.scale.set(hr[0] * 1.08, hr[1] * 1.06, hr[2] * 1.08); cap.rotation.x = -.5; cap.position.copy(local(...hc)); head.add(cap);
    // short trimmed beard following the jaw
    // short beard: a thin layer that follows the jaw surface (built from the SDF so it hugs the face)
    {
      const bgeo = new THREE.SphereGeometry(1, 64, 24, Math.PI * .5 - 1.2, 2.4, Math.PI * .655, Math.PI * .17);
      const bp = bgeo.attributes.position, c0 = [0, 1.66 * s, .01 * s];
      for (let i = 0; i < bp.count; i++) {
        const dx = bp.getX(i), dy = bp.getY(i), dz = bp.getZ(i);
        let a = 0, b = .2 * s; // march out from the centre along the direction to find the skin, then sit just above it
        for (let k = 0; k < 24; k++) { const m = (a + b) / 2; if (sdf(c0[0] + dx * m, c0[1] + dy * m, c0[2] + dz * m) < 0) a = m; else b = m; }
        const r = a + .006 * s * (1 + .3 * Math.sin(i * 12.9898));
        bp.setXYZ(i, c0[0] + dx * r - hp[0], c0[1] + dy * r - hp[1], c0[2] + dz * r - hp[2]);
      }
      bgeo.computeVertexNormals();
      const beard = new THREE.Mesh(bgeo, mat('#22150e', 1)); head.add(beard);
    }
    const must = new THREE.Mesh(new THREE.CapsuleGeometry(.007 * s, .03 * s, 4, 8), hairM); must.rotation.z = Math.PI / 2;
    must.position.copy(local(0, 1.663 * s, surfZ(0, 1.663 * s) + .002)); head.add(must);
  }
  head.traverse(o => { if (o.isMesh) { o.castShadow = true; } });

  // ---- posing API
  const restPos = Object.fromEntries(names.map(n => [n, bones[n].position.clone()]));
  function setPose(p) {
    for (const n of names) {
      const r = p[n] || [0, 0, 0];
      bones[n].rotation.set(r[0], r[1], r[2]);
      bones[n].position.copy(restPos[n]);
    }
    if (p.hipsOffset) bones.hips.position.add(new THREE.Vector3(...p.hipsOffset));
  }
  function setFace({ blink = 0, smile = .5, open = 0, look = [0, 0] } = {}) {
    face.eyes.forEach(e => {
      e.lid.rotation.x = lerp(-1.05, 1.45, blink);
      e.iris.position.x = look[0] * .006 * s; e.iris.position.y = look[1] * .005 * s;
      e.pupil.position.x = e.iris.position.x; e.pupil.position.y = e.iris.position.y;
    });
    const m = face.mouth, o = Math.min(1, open);
    m.inner.scale.set(1, .05 + o * .85, 1); m.inner.visible = o > .03;
    m.lower.scale.y = .35 + smile * .2 + o * .55;
    m.teeth.visible = o > .12; m.teeth.scale.x = .9;
    m.mouth.scale.x = .9 + smile * .2;
  }
  setFace();

  // ---- two-bone arm IK: place a hand at a target (avatar-local, unscaled metres), elbow toward a pole direction
  const V = () => new THREE.Vector3(), Q = () => new THREE.Quaternion();
  function aim(bone, child, targetW) {
    bone.updateWorldMatrix(true, true);
    const pB = bone.getWorldPosition(V()), from = child.getWorldPosition(V()).sub(pB).normalize();
    const to = targetW.clone().sub(pB).normalize();
    const qW = Q().setFromUnitVectors(from, to).multiply(bone.getWorldQuaternion(Q()));
    bone.quaternion.copy(bone.parent.getWorldQuaternion(Q()).invert().multiply(qW));
  }
  function reachArm(n, target, pole) {
    const up = bones['uArm' + n], fa = bones['fArm' + n], hd = bones['hand' + n];
    group.updateWorldMatrix(true, true);
    const S = up.getWorldPosition(V()), E = fa.getWorldPosition(V()), Wr = hd.getWorldPosition(V());
    const l1 = E.distanceTo(S), l2 = Wr.distanceTo(E);
    const T = group.localToWorld(new THREE.Vector3(...target));
    const gq = group.getWorldQuaternion(Q()), poleW = new THREE.Vector3(...pole).applyQuaternion(gq).normalize();
    const d = T.clone().sub(S), D = Math.min(Math.max(d.length(), 1e-3), (l1 + l2) * .999), dn = d.normalize();
    const ca = (l1 * l1 + D * D - l2 * l2) / (2 * l1 * D), sa = Math.sqrt(Math.max(0, 1 - ca * ca));
    const pw = poleW.sub(dn.clone().multiplyScalar(poleW.dot(dn))).normalize();
    const elbow = S.clone().addScaledVector(dn, l1 * ca).addScaledVector(pw, l1 * sa);
    aim(up, fa, elbow);
    aim(fa, hd, S.clone().addScaledVector(dn, D));
  }
  return { group, bones, setPose, setFace, reachArm, J, s, skinned };
}

function clamp01(x) { return Math.min(1, Math.max(0, x)); }
function smooth01(x) { x = clamp01(x); return x * x * (3 - 2 * x); }
function lerp(a, b, k) { return a + (b - a) * k; }

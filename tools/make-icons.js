// Honyuka icons: "Fuki-chan", a speech-bubble mascot, translating a Japanese bubble.
// Setup: npm install @resvg/resvg-js@2.6.2 ; node tools/make-icons.js <outDir> [previewDir]
// usage: node make-icons.js <outDir> <previewDir>
const { Resvg } = require('@resvg/resvg-js');
const fs = require('fs');
const path = require('path');

const [, , outDir, previewDir] = process.argv;

const INK = '#3A3335';
const PAPER = '#FFFFFF';
const BLUSH = '#FF9EB5';
const SAKURA = '#F09AB0';
const SORA = '#8DB8E0';
const SORA_DEEP = '#6E9FD0';
const YUZU = '#FFD66B';

// Speech bubble, tail bottom-left. 100x100 units (also used by ui/Mascot.kt).
const BUBBLE =
  'M50 12 C76 12 92 27 92 45 C92 63 76 77 52 77 C47 77 43 76.5 39 75.5 L22 90 C21.2 90.7 20 90 20.4 89 L26 72.5 C15 67 8 57 8 45 C8 27 24 12 50 12 Z';
// Mirrored bubble, tail bottom-right (the Japanese "source" bubble).
const BUBBLE_MIRROR = `<path d="${BUBBLE}" transform="translate(100 0) scale(-1 1)"/>`;

function sparkle(cx, cy, r, fill) {
  const k = r * 0.28;
  return `<path d="M${cx} ${cy - r} Q${cx + k} ${cy - k} ${cx + r} ${cy} Q${cx + k} ${cy + k} ${cx} ${cy + r} Q${cx - k} ${cy + k} ${cx - r} ${cy} Q${cx - k} ${cy - k} ${cx} ${cy - r} Z" fill="${fill}"/>`;
}

/** Small "A" badge: the translated side. */
function badge(cx, cy, r) {
  return `
    <circle cx="${cx}" cy="${cy}" r="${r}" fill="${SORA}" stroke="${INK}" stroke-width="2.2"/>
    <path d="M${cx - r * 0.42} ${cy + r * 0.45} L${cx} ${cy - r * 0.5} L${cx + r * 0.42} ${cy + r * 0.45} M${cx - r * 0.25} ${cy + r * 0.12} L${cx + r * 0.25} ${cy + r * 0.12}"
      stroke="#FFFFFF" stroke-width="${r * 0.26}" fill="none" stroke-linecap="round" stroke-linejoin="round"/>`;
}

/** Fuki-chan in 100-unit space. `mono` = alpha silhouette for Android themed icons. */
function fuki({ mono = false, withBadge = true } = {}) {
  if (mono) {
    return `
      <defs><mask id="face">
        <rect x="0" y="0" width="100" height="100" fill="white"/>
        <circle cx="38" cy="43" r="5" fill="black"/>
        <circle cx="62" cy="43" r="5" fill="black"/>
        <path d="M44.5 50.5 Q47.25 54.5 50 51 Q52.75 54.5 55.5 50.5" stroke="black" stroke-width="2.6" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
      </mask></defs>
      <path d="${BUBBLE}" fill="white" mask="url(#face)"/>`;
  }
  return `
    <defs>
      <radialGradient id="paperShade" cx="40%" cy="30%" r="80%">
        <stop offset="0%" stop-color="${PAPER}"/>
        <stop offset="80%" stop-color="#FFFBF7"/>
        <stop offset="100%" stop-color="#F4ECE6"/>
      </radialGradient>
    </defs>
    <ellipse cx="52" cy="96" rx="30" ry="3.4" fill="#E58AA3" opacity="0.35"/>
    <path d="${BUBBLE}" fill="url(#paperShade)" stroke="${INK}" stroke-width="2.8" stroke-linejoin="round"/>
    <path d="M24 34 Q30 22 44 19" stroke="#FFFFFF" stroke-width="2.6" stroke-linecap="round" fill="none" opacity="0.95"/>
    <ellipse cx="28" cy="51" rx="6" ry="3.6" fill="${BLUSH}" opacity="0.75"/>
    <ellipse cx="72" cy="51" rx="6" ry="3.6" fill="${BLUSH}" opacity="0.75"/>
    <circle cx="38" cy="43" r="5" fill="${INK}"/>
    <circle cx="62" cy="43" r="5" fill="${INK}"/>
    <circle cx="39.9" cy="41" r="1.9" fill="#fff"/>
    <circle cx="63.9" cy="41" r="1.9" fill="#fff"/>
    <circle cx="36.6" cy="45.3" r="0.9" fill="#fff"/>
    <circle cx="60.6" cy="45.3" r="0.9" fill="#fff"/>
    <path d="M44.5 50.5 Q47.25 54.5 50 51 Q52.75 54.5 55.5 50.5" stroke="${INK}" stroke-width="2.1" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
    ${withBadge ? badge(85, 20, 10) : ''}
  `;
}

/** The Japanese source bubble: sky blue with a white あ. */
function sourceBubble() {
  return `
    <g fill="${SORA}" stroke="${INK}" stroke-width="2.8" stroke-linejoin="round">${BUBBLE_MIRROR}</g>
    <text x="50" y="57" font-family="Yu Gothic UI, Yu Gothic, Meiryo, MS Gothic, sans-serif" font-weight="700"
      font-size="42" text-anchor="middle" fill="#FFFFFF" stroke="#FFFFFF" stroke-width="2.4" stroke-linejoin="round">あ</text>`;
}

/** Pink paper with a manga screentone (halftone dots growing toward the bottom-right). */
function background({ size = 1024 } = {}) {
  const dots = [];
  const step = 56;
  for (let y = step / 2; y < size; y += step) {
    for (let x = step / 2 + ((y / step) % 2 ? step / 2 : 0); x < size; x += step) {
      const t = (x + y) / (2 * size); // 0 top-left → 1 bottom-right
      const r = Math.max(0, (t - 0.38) * 26);
      if (r > 0.6) dots.push(`<circle cx="${x}" cy="${y}" r="${r.toFixed(1)}" fill="#FFFFFF" opacity="0.42"/>`);
    }
  }
  return `
    <defs>
      <radialGradient id="bg" cx="35%" cy="30%" r="85%">
        <stop offset="0%" stop-color="#FFF3F6"/>
        <stop offset="55%" stop-color="#FFD3E0"/>
        <stop offset="100%" stop-color="#F7A3BD"/>
      </radialGradient>
    </defs>
    <rect width="${size}" height="${size}" fill="url(#bg)"/>
    ${dots.join('')}`;
}

/** Back bubble (あ) up-right, Fuki-chan in front down-left, a sparkle where they meet. */
/** Little curved arrow: Japanese bubble → Fuki-chan. */
function arrow() {
  const curve = 'M770 560 Q790 650 680 668';
  const head = 'M712 632 L672 668 L716 700';
  return [curve, head].map((d) => `
    <path d="${d}" stroke="${INK}" stroke-width="22" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
    <path d="${d}" stroke="${YUZU}" stroke-width="12" fill="none" stroke-linecap="round" stroke-linejoin="round"/>`).join('');
}

/** Japanese bubble up-right, Fuki-chan in front down-left, an arrow between them. */
function composition() {
  return (
    place(420, 120, 4.2, sourceBubble()) +
    place(110, 380, 5.0, fuki({ withBadge: false })) +
    arrow() +
    sparkle(200, 300, 24, '#FFFFFF') +
    sparkle(880, 760, 22, '#FFFFFF')
  );
}

const svg = (w, body) => `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${w}" viewBox="0 0 ${w} ${w}">${body}</svg>`;
const place = (x, y, scale, inner) => `<g transform="translate(${x} ${y}) scale(${scale})">${inner}</g>`;

function render(svgText, file, width) {
  const png = new Resvg(svgText, {
    fitTo: { mode: 'width', value: width },
    font: { loadSystemFonts: true, defaultFontFamily: 'Yu Gothic UI' },
  }).render().asPng();
  fs.writeFileSync(file, png);
  console.log('wrote', path.basename(file), width);
}

fs.mkdirSync(outDir, { recursive: true });
if (previewDir) fs.mkdirSync(previewDir, { recursive: true });

// Full icon: background + composition.
const full = svg(1024, background() + composition());
render(full, path.join(outDir, 'icon.png'), 1024);

// Adaptive foreground: composition scaled into the 66% safe zone (centered).
const fgInner = place(174, 174, 0.66, composition());
render(svg(1024, fgInner), path.join(outDir, 'android-icon-foreground.png'), 1024);
render(svg(1024, background()), path.join(outDir, 'android-icon-background.png'), 1024);
const mono = place(174, 174, 0.66,
  place(392, 150, 4.1, `<g fill="white" opacity="0.55">${BUBBLE_MIRROR}</g>`) + place(150, 360, 5.0, fuki({ mono: true })));
render(svg(1024, mono), path.join(outDir, 'android-icon-monochrome.png'), 1024);

// Mascot alone (README / splash).
render(svg(1024, place(112, 100, 8, fuki())), path.join(outDir, 'mascot.png'), 1024);

if (previewDir) {
  const circle = svg(1024,
    `<defs><clipPath id="c"><circle cx="512" cy="512" r="512"/></clipPath></defs>
     <g clip-path="url(#c)"><g transform="translate(-256 -256) scale(1.5)">${background()}${fgInner}</g></g>`);
  render(circle, path.join(previewDir, 'preview-adaptive-circle.png'), 512);
  render(full, path.join(previewDir, 'preview-full.png'), 512);
}

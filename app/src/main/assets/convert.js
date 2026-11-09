import init, { HwpDocument } from './rhwp.js';

const PX_TO_PT = 0.75;
const HWPUNIT_PER_PX = 75;
// Where a line's baseline sits inside the box the layout reports.
const BASELINE_RATIO = 0.85;
// A canvas this size is safe on tablets and still gives A3 about 260 dpi.
const MAX_PIXELS = 14e6;
const FACES = [
  "400 16px 'Noto Serif KR'", "700 16px 'Noto Serif KR'",
  "400 16px 'Noto Sans KR'", "700 16px 'Noto Sans KR'",
  "400 16px 'Pretendard'", "700 16px 'Pretendard'",
  "400 16px 'Latin Modern Math'",
  "400 16px 'RHWP Old Hangul'",
];

const bridge = window.Android;

// The engine calls this while laying out lines, so it must exist before init().
let measureContext = null;
let measureFont = '';
globalThis.measureTextWidth = (font, text) => {
  measureContext ??= document.createElement('canvas').getContext('2d');
  if (font !== measureFont) {
    measureContext.font = font;
    measureFont = font;
  }
  return measureContext.measureText(text).width;
};

// Equations saved by Hancom draw private-use codes from fonts only Hancom ships.
// Without those fonts the codes come out as stray accents, so translate them.
const GREEK_LOWER = 'αβγδεζηθικλμνξοπρστυφχψω';
const GREEK_UPPER = 'ΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩ';
const EQUATION_SYMBOLS = {
  0xe03d: '0', 0xe042: '%', 0xe044: '(', 0xe045: ')', 0xe046: '−', 0xe047: '=', 0xe048: '+',
  0xe04f: ':', 0xe050: "'", 0xe052: ',', 0xe055: '<', 0xe056: '>', 0xe067: '∑',
};

function equationChar(char) {
  const code = char.codePointAt(0);
  if (code >= 0xe0e5 && code < 0xe0e5 + 26) {
    const index = code - 0xe0e5;
    return index === 7 ? 'ℎ' : String.fromCodePoint(0x1d44e + index); // math italic a-z
  }
  if (code >= 0xe000 && code < 0xe000 + 26) return String.fromCharCode(65 + code - 0xe000);
  if (code >= 0xe034 && code <= 0xe03c) return String(code - 0xe033);
  if (code >= 0xe09d && code < 0xe09d + GREEK_LOWER.length) return GREEK_LOWER[code - 0xe09d];
  if (code >= 0xe085 && code < 0xe085 + GREEK_UPPER.length) return GREEK_UPPER[code - 0xe085];
  return EQUATION_SYMBOLS[code] ?? char;
}

// A tone mark (방점) is stored after its syllable and belongs on the syllable's
// left, but the engine paints it in storage order. On the right it reads as the
// next syllable's mark, so the pair is swapped inside the space it already has.
const TONE_MARK = /^[〮〯]+$/;
const HANGUL = /[가-힣ᄀ-ᇿꥠ-ꥼힰ-ퟻ]/;
const DOUBLE_TONE_DRAWN_AS = '⁚'; // the engine's stand-in for a double dot
const graphemes = new Intl.Segmenter('ko', { granularity: 'grapheme' });
let toneMoves = [];

function clustersOf(run) {
  const clusters = [];
  let index = 0;
  for (const { segment } of graphemes.segment(run.text ?? '')) {
    const length = Array.from(segment).length;
    const start = run.charX?.[index];
    const end = run.charX?.[index + length];
    index += length;
    if (start === undefined || end === undefined) continue;
    clusters.push({
      text: segment, x: run.x + start, width: end - start, top: run.y, bottom: run.y + run.h,
    });
  }
  return clusters;
}

const isNextTo = (a, b) => b.top < a.bottom && a.top < b.bottom && Math.abs(a.x + a.width - b.x) < 2;

function findToneMoves(layoutJson, scale) {
  const { runs = [] } = JSON.parse(layoutJson);
  if (!runs.some((run) => /[〮〯]/.test(run.text ?? ''))) return [];

  const paragraphs = new Map();
  for (const run of runs) {
    const key = `${run.secIdx}:${run.paraIdx}`;
    paragraphs.set(key, [...(paragraphs.get(key) ?? []), ...clustersOf(run)]);
  }

  const moves = [];
  for (const clusters of paragraphs.values()) {
    const pairs = [];
    let leadingMarks = 0;
    let trailingMarks = 0;
    clusters.forEach((cluster, i) => {
      if (!TONE_MARK.test(cluster.text)) return;
      const previous = clusters[i - 1];
      const next = clusters[i + 1];
      if (previous && HANGUL.test(previous.text) && isNextTo(previous, cluster)) {
        pairs.push([previous, cluster]);
      } else {
        leadingMarks++;
      }
      if (!next || !HANGUL.test(next.text) || !isNextTo(cluster, next)) trailingMarks++;
    });
    // Marks that open words were typed in front of their syllables already.
    if (leadingMarks > trailingMarks) continue;

    for (const [syllable, mark] of pairs) {
      const top = Math.min(syllable.top, mark.top) * scale;
      const bottom = Math.max(syllable.bottom, mark.bottom) * scale;
      moves.push({
        isMark: false, left: (syllable.x - 1.5) * scale, right: (syllable.x + 1.5) * scale,
        top, bottom, shift: mark.width * scale,
      });
      moves.push({
        isMark: true, left: (mark.x - 1.5) * scale, right: (mark.x + mark.width + 1.5) * scale,
        top, bottom, shift: (syllable.x - mark.x) * scale,
      });
    }
  }
  return moves;
}

function toneShift(isMark, x, y) {
  for (const move of toneMoves) {
    if (move.isMark === isMark && x >= move.left && x <= move.right && y >= move.top && y <= move.bottom) {
      return move.shift;
    }
  }
  return 0;
}

const EQUATION_FONT = /"?(?:HyhwpEQ|HancomEQN|RHWP Hancom Legacy Equation)"?/i;
const nativeFillText = CanvasRenderingContext2D.prototype.fillText;
CanvasRenderingContext2D.prototype.fillText = function (text, x, y, ...rest) {
  const font = this.font;
  if (EQUATION_FONT.test(font)) {
    this.font = font.replace(EQUATION_FONT, "'Latin Modern Math', serif");
    nativeFillText.call(this, Array.from(text, equationChar).join(''), x, y, ...rest);
    this.font = font;
    return;
  }
  let drawX = x;
  if (toneMoves.length) {
    const m = this.getTransform();
    const isMark = text === DOUBLE_TONE_DRAWN_AS || TONE_MARK.test(text);
    drawX = x + toneShift(isMark, m.e + x * m.a, m.f + y * m.d) / m.a;
  }
  const drawY = y + shiftAt(this, x, y);
  if (text === DOUBLE_TONE_DRAWN_AS) {
    drawDoubleTone(this, drawX, drawY);
    return;
  }
  nativeFillText.call(this, text, drawX, drawY, ...rest);
};

// Android fonts have no glyph for U+205A, so draw it the way the engine draws a
// single dot: as circles, sized and placed relative to the font.
function drawDoubleTone(context, x, baseline) {
  const size = parseFloat(context.font.match(/([\d.]+)px/)?.[1] ?? '16');
  const centerX = x + size * 0.12;
  const centerY = baseline - size * 0.345;
  for (const offset of [-0.16, 0.16]) {
    context.beginPath();
    nativeArc.call(context, centerX, centerY + size * offset, size * 0.06, 0, Math.PI * 2);
    context.fill();
  }
}

// The engine draws a single tone mark as a small filled circle.
const nativeArc = CanvasRenderingContext2D.prototype.arc;
CanvasRenderingContext2D.prototype.arc = function (x, y, ...rest) {
  let drawX = x;
  if (toneMoves.length) {
    const m = this.getTransform();
    drawX = x + toneShift(true, m.e + x * m.a, m.f + y * m.d) / m.a;
  }
  nativeArc.call(this, drawX, y + shiftAt(this, x, y), ...rest);
};

// Exam papers often open with a full-width title table anchored to the first
// paragraph. Hancom reserves its band once, for every column. The engine reserves
// it, draws the table below the band, then pushes the anchor's column down again,
// so the table lands on the next column's text. These shifts undo that.
let bands = [];
let bandScale = 1;
let pathShift = 0;

function bandShiftInPage(x, y) {
  for (const band of bands) {
    if (x >= band.left && x <= band.right && y >= band.top && y <= band.bottom
        && (!band.accepts || band.accepts(x, y))) {
      return band.shift;
    }
  }
  return 0;
}

/** The vertical correction, in the context's own units, for something drawn at (x, y). */
function shiftAt(context, x, y) {
  if (!bands.length) return 0;
  const m = context.getTransform();
  return (bandShiftInPage((m.e + x * m.a) / bandScale, (m.f + y * m.d) / bandScale) * bandScale) / m.d;
}

const canvasApi = CanvasRenderingContext2D.prototype;

for (const name of ['fillRect', 'strokeRect', 'clearRect', 'rect', 'ellipse']) {
  const native = canvasApi[name];
  canvasApi[name] = function (x, y, ...rest) {
    native.call(this, x, y + shiftAt(this, x, y), ...rest);
  };
}

const nativeStrokeText = canvasApi.strokeText;
canvasApi.strokeText = function (text, x, y, ...rest) {
  nativeStrokeText.call(this, text, x, y + shiftAt(this, x, y), ...rest);
};

// A path moves as one piece, decided by where it starts.
const nativeMoveTo = canvasApi.moveTo;
canvasApi.moveTo = function (x, y) {
  pathShift = shiftAt(this, x, y);
  nativeMoveTo.call(this, x, y + pathShift);
};

const nativeLineTo = canvasApi.lineTo;
canvasApi.lineTo = function (x, y) {
  nativeLineTo.call(this, x, y + pathShift);
};

const nativeQuadratic = canvasApi.quadraticCurveTo;
canvasApi.quadraticCurveTo = function (cx, cy, x, y) {
  nativeQuadratic.call(this, cx, cy + pathShift, x, y + pathShift);
};

const nativeBezier = canvasApi.bezierCurveTo;
canvasApi.bezierCurveTo = function (ax, ay, bx, by, x, y) {
  nativeBezier.call(this, ax, ay + pathShift, bx, by + pathShift, x, y + pathShift);
};

const nativeDrawImage = canvasApi.drawImage;
canvasApi.drawImage = function (image, ...args) {
  // The destination position comes first, unless a source rectangle precedes it.
  const yIndex = args.length === 8 ? 5 : 1;
  args[yIndex] += shiftAt(this, args[yIndex - 1], args[yIndex]);
  nativeDrawImage.call(this, image, ...args);
};

// The layout can list a table the property lookup cannot address, such as one
// nested inside another object. Those are simply not candidates for a correction.
function tableProperties(doc, table) {
  try {
    return JSON.parse(doc.getTableProperties(table.secIdx, table.paraIdx, table.controlIdx));
  } catch {
    return null;
  }
}

function findHeaderBands(doc, pageIndex, info, layoutJson) {
  if (info.columns.length < 2) return [];
  const { controls = [] } = JSON.parse(doc.getPageControlLayout(pageIndex));
  const { runs = [] } = JSON.parse(layoutJson);
  const bodyTop = info.marginTop + info.marginHeader;
  const bodyWidth = info.bodyRight - info.bodyLeft;
  const found = [];

  for (const table of controls) {
    if (table.type !== 'table' || table.wrap !== 'topAndBottom' || table.w < bodyWidth * 0.8) continue;
    const props = tableProperties(doc, table);
    if (!props || props.treatAsChar || props.vertRelTo !== 'Para') continue;

    const body = runs.filter((run) => !run.cellPath && run.secIdx === table.secIdx);
    const anchor = body.find((run) => run.paraIdx === table.paraIdx);
    if (!anchor) continue;
    const pushed = anchor.y - bodyTop;
    if (pushed < 2 || Math.abs(table.y - anchor.y) > 3) continue;

    const column = info.columns.find((c) => anchor.x >= c.x - 2 && anchor.x <= c.x + c.width);
    if (!column) continue;
    const inColumn = (run) => run.x >= column.x - 2 && run.x <= column.x + column.width + 2;
    if (body.some((run) => inColumn(run) && run.text.trim() && run.y < anchor.y - 1)) continue;

    const cellRuns = runs.filter((run) => run.cellPath && run.parentParaIdx === table.paraIdx
      && run.controlIdx === table.controlIdx);
    const cellEdges = table.cells.flatMap((cell) => [cell.x, cell.x + cell.w]);
    const nextColumnX = info.columns.find((c) => c.x > column.x)?.x ?? Infinity;
    const correctY = bodyTop + (props.vertOffset + props.outerTop) / HWPUNIT_PER_PX;

    found.push({
      left: table.x - 2, right: table.x + table.w + 2, top: table.y - 2, bottom: table.y + table.h + 2,
      shift: correctY - table.y,
      // The band overlaps the next column, whose own text must stay where it is.
      accepts: (x, y) => x < nextColumnX
        || cellEdges.some((edge) => Math.abs(x - edge) < 1.5)
        || cellRuns.some((run) => x >= run.x - 1 && x <= run.x + run.w + 1 && y >= run.y - 2 && y <= run.y + run.h + 2),
    });

    const next = body.find((run) => run.paraIdx === table.paraIdx + 1 && inColumn(run));
    if (next && next.y - anchor.y > pushed) {
      found.push({
        left: column.x - 2, right: column.x + column.width + 2, top: next.y - 2, bottom: info.height, shift: -pushed,
      });
    }
  }
  return found;
}

/** One TJ array per text run, placed to match the rendered page image. */
function invisibleText(layoutJson, pageHeightPx) {
  const { runs = [] } = JSON.parse(layoutJson);
  const ops = [];
  for (const run of runs) {
    // Old Hangul in private-use codes has a real-Unicode form for display.
    const chars = Array.from(run.displayText ?? run.text ?? '');
    const edges = run.displayCharX ?? run.charX ?? [];
    const size = run.fontSize * PX_TO_PT;
    if (!chars.length || edges.length !== chars.length + 1 || !(size > 0)) continue;
    if (!chars.some((c) => c.trim())) continue;

    const parts = [];
    let carried = 0;
    chars.forEach((char, i) => {
      const advance = (edges[i + 1] - edges[i]) * PX_TO_PT + carried;
      const code = char.codePointAt(0);
      if (code > 0xffff) {
        carried = advance;
        return;
      }
      carried = 0;
      const hex = code.toString(16).padStart(4, '0');
      // The font's advance is one em; TJ subtracts the difference in 1/1000 em.
      parts.push(`<${hex}>${Math.round(1000 - (advance / size) * 1000)}`);
    });
    if (!parts.length) continue;

    const top = run.y + bandShiftInPage(run.x, run.y + run.h / 2);
    const baseline = pageHeightPx - (top + run.h * BASELINE_RATIO);
    ops.push(
      `BT 3 Tr /F1 ${size.toFixed(2)} Tf 1 0 0 1 ${(run.x * PX_TO_PT).toFixed(2)} ` +
      `${(baseline * PX_TO_PT).toFixed(2)} Tm [${parts.join('')}] TJ ET`,
    );
  }
  return ops.join('\n');
}

window.convert = async (scale, quality) => {
  let doc = null;
  try {
    const response = await fetch('/input/doc');
    if (!response.ok) throw new Error('입력 파일을 읽지 못했어요.');
    doc = new HwpDocument(new Uint8Array(await response.arrayBuffer()));

    const pageCount = doc.pageCount();
    if (pageCount < 1) throw new Error('쪽이 없는 문서예요.');
    bridge.begin(pageCount);

    const canvas = document.createElement('canvas');
    // Every page is read straight back, so keep it out of GPU memory.
    canvas.getContext('2d', { willReadFrequently: true });
    for (let i = 0; i < pageCount; i++) {
      const info = JSON.parse(doc.getPageInfo(i));
      const limit = Math.sqrt(MAX_PIXELS / (info.width * info.height));
      const pageScale = Math.min(scale, limit);
      const layout = doc.getPageTextLayout(i);
      toneMoves = findToneMoves(layout, pageScale);
      bands = findHeaderBands(doc, i, info, layout);
      bandScale = pageScale;
      doc.renderPageToCanvas(i, canvas, pageScale);
      toneMoves = [];
      const jpeg = canvas.toDataURL('image/jpeg', quality);
      const textLayer = invisibleText(layout, info.height);
      bands = [];
      bridge.page(
        i,
        jpeg.slice(jpeg.indexOf(',') + 1),
        canvas.width,
        canvas.height,
        info.width * PX_TO_PT,
        info.height * PX_TO_PT,
        textLayer,
      );
      // Yield so the page's memory can be reclaimed between renders.
      await new Promise((resolve) => setTimeout(resolve));
    }
    bridge.done();
  } catch (error) {
    bridge.fail(friendly(String(error?.message ?? error)));
  } finally {
    doc?.free();
  }
};

function friendly(message) {
  if (/비밀번호|암호|password|encrypt/i.test(message)) {
    return '비밀번호가 걸린 문서라 변환할 수 없어요.';
  }
  if (/UNSUPPORTED_FILE_FORMAT|지원하지 않는 포맷/.test(message)) {
    return 'HWP나 HWPX 파일이 아니거나 지원하지 않는 형식이에요.';
  }
  return message;
}

try {
  await Promise.all(FACES.map((face) => document.fonts.load(face, '가Aa1\u1112\u119e\u4e2d')));
  await init({ module_or_path: 'rhwp_bg.wasm' });
  bridge.ready();
} catch (error) {
  bridge.fail(String(error?.message ?? error));
}

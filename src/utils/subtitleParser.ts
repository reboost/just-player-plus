import { SubtitleCue } from '../types/player';

/**
 * Normalizes subtitle content text by removing BOM and normalizing line breaks
 */
function cleanText(raw: string): string {
  if (raw.charCodeAt(0) === 0xfeff) {
    raw = raw.slice(1);
  }
  return raw.replace(/\r\n/g, '\n').replace(/\r/g, '\n').trim();
}

/**
 * Parses timestamp string "00:01:23,456" or "00:01:23.456" or "01:23.456" into seconds
 */
function parseTimestampToSeconds(timestamp: string): number {
  const clean = timestamp.trim().replace(',', '.');
  const parts = clean.split(':');
  if (parts.length === 3) {
    const hours = parseFloat(parts[0]) || 0;
    const minutes = parseFloat(parts[1]) || 0;
    const seconds = parseFloat(parts[2]) || 0;
    return hours * 3600 + minutes * 60 + seconds;
  } else if (parts.length === 2) {
    const minutes = parseFloat(parts[0]) || 0;
    const seconds = parseFloat(parts[1]) || 0;
    return minutes * 60 + seconds;
  }
  return parseFloat(clean) || 0;
}

/**
 * Parses SRT (SubRip) format
 */
export function parseSRT(content: string): SubtitleCue[] {
  const text = cleanText(content);
  const blocks = text.split(/\n\n+/);
  const cues: SubtitleCue[] = [];

  for (const block of blocks) {
    const lines = block.split('\n');
    if (lines.length < 2) continue;

    // Check if line 0 is numeric index or timestamp
    let timeLineIndex = 0;
    if (/^\d+$/.test(lines[0].trim()) && lines.length >= 2) {
      timeLineIndex = 1;
    }

    const timeLine = lines[timeLineIndex];
    if (!timeLine || !timeLine.includes('-->')) continue;

    const [startStr, endStr] = timeLine.split('-->');
    if (!startStr || !endStr) continue;

    const startTime = parseTimestampToSeconds(startStr);
    const endTime = parseTimestampToSeconds(endStr);

    const textLines = lines.slice(timeLineIndex + 1).join('\n').trim();
    if (!textLines) continue;

    cues.push({
      startTime,
      endTime,
      text: stripHtml(textLines),
      html: sanitizeHtml(textLines),
    });
  }

  return cues;
}

/**
 * Parses WebVTT format
 */
export function parseVTT(content: string): SubtitleCue[] {
  const text = cleanText(content);
  const lines = text.split('\n');
  const cues: SubtitleCue[] = [];
  let i = 0;

  // Skip WEBVTT header
  while (i < lines.length && !lines[i].includes('-->')) {
    i++;
  }

  while (i < lines.length) {
    const line = lines[i].trim();
    if (line.includes('-->')) {
      const [startStr, endStrWithSettings] = line.split('-->');
      const startStrClean = startStr.trim();
      const endStrClean = endStrWithSettings.trim().split(' ')[0];

      const startTime = parseTimestampToSeconds(startStrClean);
      const endTime = parseTimestampToSeconds(endStrClean);

      i++;
      const textLines: string[] = [];
      while (i < lines.length && lines[i].trim() !== '') {
        textLines.push(lines[i]);
        i++;
      }

      const cueText = textLines.join('\n').trim();
      if (cueText) {
        cues.push({
          startTime,
          endTime,
          text: stripHtml(cueText),
          html: sanitizeHtml(cueText),
        });
      }
    } else {
      i++;
    }
  }

  return cues;
}

/**
 * MicroDVD and MPL2 parser ported directly from MicroDvdParser.java in Just Player+
 * MicroDVD format: {start_frame}{end_frame}Text|Second line
 * MPL2 format: [start_decisec][end_decisec]Text|Second line
 */
export function parseMicroDvdOrMpl2(content: string, videoFps = 23.976): SubtitleCue[] {
  const text = cleanText(content);
  const lines = text.split('\n');
  const cues: SubtitleCue[] = [];

  const lineRegex = /^[[{](-?\d+)[\]}][[{](-?\d+)[\]}](.*)$/;
  let fps = videoFps > 0 ? videoFps : 23.976;
  let isMpl2 = false;

  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (!line) continue;

    const match = lineRegex.exec(line);
    if (!match) continue;

    const startVal = parseInt(match[1], 10);
    const endVal = parseInt(match[2], 10);
    const rawContent = match[3] || '';

    // Check if line uses MPL2 brackets: [start][end]
    if (line.startsWith('[')) {
      isMpl2 = true;
    }

    // MicroDVD can have an initial frame rate header: {1}{1}23.976 or {1}{1}25.000
    if (!isMpl2 && startVal <= 1 && endVal <= 1 && /^\d+(\.\d+)?$/.test(rawContent.trim())) {
      const parsedFps = parseFloat(rawContent.trim());
      if (parsedFps > 0) {
        fps = parsedFps;
        continue;
      }
    }

    let startTime = 0;
    let endTime = 0;

    if (isMpl2) {
      // MPL2 uses tenths of a second (10 fps)
      startTime = Math.max(0, startVal) / 10.0;
      endTime = Math.max(0, endVal) / 10.0;
    } else {
      // MicroDVD uses frames
      startTime = Math.max(0, startVal) / fps;
      endTime = Math.max(0, endVal) / fps;
    }

    if (endTime < startTime) {
      endTime = startTime + 2.0;
    }

    // Process MicroDVD tags:
    // {y:i} italic, {y:b} bold, {y:u} underline
    // {c:$RRGGBB} color
    // | line break, / leading line italic
    const parsedText = processMicroDvdText(rawContent);

    cues.push({
      startTime,
      endTime,
      text: parsedText.plain,
      html: parsedText.html,
      color: parsedText.color,
      bold: parsedText.bold,
      italic: parsedText.italic,
      underline: parsedText.underline,
    });
  }

  return cues;
}

interface ProcessedText {
  plain: string;
  html: string;
  color?: string;
  bold?: boolean;
  italic?: boolean;
  underline?: boolean;
}

function processMicroDvdText(raw: string): ProcessedText {
  let bold = false;
  let italic = false;
  let underline = false;
  let color: string | undefined = undefined;

  // Extract style tags {y:...}
  let text = raw.replace(/\{y:([^}]*)\}/gi, (_, styles) => {
    const s = styles.toLowerCase();
    if (s.includes('b')) bold = true;
    if (s.includes('i')) italic = true;
    if (s.includes('u')) underline = true;
    return '';
  });

  // Extract color tags {c:...}
  text = text.replace(/\{c:(\$[0-9a-fA-F]{6}|[a-zA-Z]+)\}/gi, (_, c) => {
    if (c.startsWith('$')) {
      color = '#' + c.slice(1);
    } else {
      color = c;
    }
    return '';
  });

  // Strip other MicroDVD tags like {P:...}, {o:...}
  text = text.replace(/\{[A-Za-z]:[^}]*\}/g, '');

  // Handle pipe line breaks and escaped pipes
  const placeholder = '__PIPE_ESC__';
  text = text.replace(/\\\|/g, placeholder).replace(/\|/g, '\n').replace(new RegExp(placeholder, 'g'), '|');

  // Handle leading '/' for line italics
  const lines = text.split('\n').map((l) => {
    if (l.startsWith('/')) {
      return `<i>${l.slice(1)}</i>`;
    }
    return l;
  });

  let html = lines.join('<br>');
  if (bold) html = `<b>${html}</b>`;
  if (italic) html = `<i>${html}</i>`;
  if (underline) html = `<u>${html}</u>`;
  if (color) html = `<span style="color:${color}">${html}</span>`;

  return {
    plain: stripHtml(html).replace(/<br>/g, '\n'),
    html,
    color,
    bold,
    italic,
    underline,
  };
}

function stripHtml(html: string): string {
  return html.replace(/<[^>]*>/g, '').trim();
}

function sanitizeHtml(html: string): string {
  return html
    .replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '')
    .replace(/\n/g, '<br>');
}

/**
 * Auto-detects subtitle format and parses into cues
 */
export function parseSubtitles(content: string, filename = ''): SubtitleCue[] {
  const lower = filename.toLowerCase();
  const trimmed = content.trim();

  if (lower.endsWith('.vtt') || trimmed.startsWith('WEBVTT')) {
    return parseVTT(content);
  }

  if (lower.endsWith('.sub') || trimmed.startsWith('{') || trimmed.startsWith('[')) {
    const cues = parseMicroDvdOrMpl2(content);
    if (cues.length > 0) return cues;
  }

  // Fallback to SRT
  return parseSRT(content);
}

/**
 * Applies delay in milliseconds to subtitle cues
 */
export function applyDelay(cues: SubtitleCue[], delayMs: number): SubtitleCue[] {
  if (!delayMs) return cues;
  const delaySec = delayMs / 1000.0;
  return cues.map((cue) => ({
    ...cue,
    startTime: Math.max(0, cue.startTime + delaySec),
    endTime: Math.max(0, cue.endTime + delaySec),
  }));
}

export type SubtitleEdgeType =
  | 'Default'
  | 'None'
  | 'Outline'
  | 'DropShadow'
  | 'OutlineShadow'
  | 'Raised'
  | 'Depressed';

export type SubtitleTypeface = 'Regular' | 'Medium' | 'Bold' | 'Custom';

export type ResizeMode = 'fit' | 'crop' | 'stretch' | 'zoom';

export type OrientationMode = 'video' | 'sensor' | 'landscape' | 'portrait';

export interface SubtitleCue {
  id?: string;
  startTime: number; // in seconds
  endTime: number;   // in seconds
  text: string;
  html?: string;
  color?: string;
  bold?: boolean;
  italic?: boolean;
  underline?: boolean;
}

export interface SubtitleTrack {
  id: string;
  label: string;
  language?: string;
  src?: string;
  content?: string;
  format?: 'srt' | 'vtt' | 'microdvd' | 'mpl2' | 'ass';
  cues?: SubtitleCue[];
  selected?: boolean;
}

export interface AudioTrack {
  id: string;
  label: string;
  language?: string;
  selected?: boolean;
}

export interface MediaItem {
  id: string;
  title: string;
  url: string;
  poster?: string;
  type?: string;
  duration?: number;
  subtitles?: SubtitleTrack[];
  audioTracks?: AudioTrack[];
}

export interface PlayerPrefs {
  // General
  fileAccess: 'auto' | 'mediastore' | 'legacy';
  languageAudio: string;
  frameRateMatching: boolean;
  autoPiP: boolean;
  skipSilence: boolean;
  repeatToggle: boolean;
  brightness: number; // 0 to 100
  volume: number;     // 0 to 200 (100 is standard, >100 is boost)
  speed: number;      // 0.25 to 3.0
  resizeMode: ResizeMode;
  orientation: OrientationMode;
  scale: number;      // 1.0 = 100%

  // Subtitles
  subtitleVerticalPosition: number; // -10 to +10 (default 0)
  subtitleSize: number;             // -10 to +10 (default 0)
  subtitleEdgeType: SubtitleEdgeType;
  subtitleTypeface: SubtitleTypeface;
  subtitleCustomFontEnabled: boolean;
  subtitleCustomFontName: string;
  subtitleCustomFontDataUrl?: string;
  subtitleStyleEmbedded: boolean;
  subtitleDelayMs: number;          // ms offset

  // Advanced / Dangerous
  decoderPriority: 'prefer_device' | 'prefer_app' | 'only_device';
  tunneling: boolean;
  mapDV7ToHevc: boolean;
  isTvBoxMode: boolean;
}

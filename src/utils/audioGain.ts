/**
 * Web Audio API Volume Booster / LoudnessEnhancer for Just Player+
 */
export class AudioBooster {
  private audioCtx: AudioContext | null = null;
  private sourceNode: MediaElementAudioSourceNode | null = null;
  private gainNode: GainNode | null = null;
  private connectedElement: HTMLMediaElement | null = null;

  public attach(mediaElement: HTMLMediaElement) {
    if (this.connectedElement === mediaElement && this.gainNode) {
      return;
    }

    try {
      const AudioCtxClass = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      if (!AudioCtxClass) return;

      if (!this.audioCtx) {
        this.audioCtx = new AudioCtxClass();
      }

      if (this.audioCtx.state === 'suspended') {
        const resumeAudio = () => {
          this.audioCtx?.resume();
          window.removeEventListener('click', resumeAudio);
          window.removeEventListener('keydown', resumeAudio);
        };
        window.addEventListener('click', resumeAudio);
        window.addEventListener('keydown', resumeAudio);
      }

      if (this.sourceNode) {
        try {
          this.sourceNode.disconnect();
        } catch {
          // ignore disconnect errors
        }
      }

      this.sourceNode = this.audioCtx.createMediaElementSource(mediaElement);
      this.gainNode = this.audioCtx.createGain();
      this.sourceNode.connect(this.gainNode);
      this.gainNode.connect(this.audioCtx.destination);
      this.connectedElement = mediaElement;
    } catch {
      // Browsers may restrict cross-origin media audio nodes if CORS is missing
    }
  }

  public setVolume(volumeLevel: number) {
    // volumeLevel: 0 to 200
    // 0..100 = standard volume
    // 101..200 = boost up to 2.0x gain
    if (!this.connectedElement) return;

    if (volumeLevel <= 100) {
      this.connectedElement.volume = Math.max(0, Math.min(1, volumeLevel / 100));
      if (this.gainNode) {
        this.gainNode.gain.value = 1.0;
      }
    } else {
      this.connectedElement.volume = 1.0;
      const boostGain = 1.0 + (volumeLevel - 100) / 100; // 1.0 to 2.0
      if (this.gainNode) {
        this.gainNode.gain.value = boostGain;
      }
    }

    if (this.audioCtx && this.audioCtx.state === 'suspended') {
      this.audioCtx.resume();
    }
  }

  public release() {
    if (this.sourceNode) {
      try {
        this.sourceNode.disconnect();
      } catch {
        // ignore
      }
    }
    if (this.audioCtx) {
      try {
        this.audioCtx.close();
      } catch {
        // ignore
      }
    }
    this.audioCtx = null;
    this.gainNode = null;
    this.sourceNode = null;
    this.connectedElement = null;
  }
}

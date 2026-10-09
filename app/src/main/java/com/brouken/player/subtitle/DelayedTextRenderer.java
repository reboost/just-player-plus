package com.brouken.player.subtitle;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.BaseRenderer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.text.SubtitleDecoderFactory;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.extractor.text.CueDecoder;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.Subtitle;
import androidx.media3.extractor.text.SubtitleDecoder;
import androidx.media3.extractor.text.SubtitleDecoderException;
import androidx.media3.extractor.text.SubtitleInputBuffer;
import androidx.media3.extractor.text.SubtitleOutputBuffer;

import com.brouken.player.subtitle.parser.OffsetSubtitleParserFactory;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

@UnstableApi
public final class DelayedTextRenderer extends BaseRenderer implements Handler.Callback {

    public static final int MSG_SET_DELAY = 10001;
    private static final String TAG = "DelayedTextRenderer";
    private static final int MSG_UPDATE_OUTPUT = 1;

    private static final int REPLACEMENT_STATE_NONE = 0;
    private static final int REPLACEMENT_STATE_SIGNAL_END_OF_STREAM = 1;
    private static final int REPLACEMENT_STATE_WAIT_END_OF_STREAM = 2;

    private static final long CUE_PRUNE_WINDOW_US = 600_000_000L; // 10 minutes

    private final CueDecoder cueDecoder;
    private final DecoderInputBuffer cueDecoderInputBuffer;
    private final SubtitleDecoderFactory subtitleDecoderFactory;
    private final AtomicInteger subtitleDelayMs;
    private final Handler outputHandler;
    private final TextOutput output;
    private final FormatHolder formatHolder;

    @Nullable
    private DelayedCuesResolver cuesResolver;
    @Nullable
    private Format streamFormat;

    private boolean waitingForKeyFrame;
    private int decoderReplacementState;
    @Nullable
    private SubtitleDecoder subtitleDecoder;
    @Nullable
    private SubtitleInputBuffer nextSubtitleInputBuffer;
    @Nullable
    private SubtitleOutputBuffer subtitle;
    @Nullable
    private SubtitleOutputBuffer nextSubtitle;
    private int nextSubtitleEventIndex;

    private boolean inputStreamEnded;
    private boolean outputStreamEnded;
    private long lastRendererPositionUs;
    private long finalStreamEndPositionUs;
    private boolean legacyDecodingEnabled;

    private ImmutableList<Cue> lastRenderedCues = ImmutableList.of();

    public DelayedTextRenderer(TextOutput output, @Nullable Looper outputLooper, AtomicInteger subtitleDelayMs) {
        this(output, outputLooper, SubtitleDecoderFactory.DEFAULT, subtitleDelayMs);
    }

    public DelayedTextRenderer(TextOutput output, @Nullable Looper outputLooper,
                               SubtitleDecoderFactory subtitleDecoderFactory, AtomicInteger subtitleDelayMs) {
        super(C.TRACK_TYPE_TEXT);
        this.output = Preconditions.checkNotNull(output);
        this.outputHandler = outputLooper == null ? null : Util.createHandler(outputLooper, this);
        this.subtitleDecoderFactory = subtitleDecoderFactory;
        this.subtitleDelayMs = subtitleDelayMs;
        this.cueDecoder = new CueDecoder();
        this.cueDecoderInputBuffer = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
        this.formatHolder = new FormatHolder();
        this.finalStreamEndPositionUs = C.TIME_UNSET;
        this.lastRendererPositionUs = C.TIME_UNSET;
        this.legacyDecodingEnabled = false;
    }

    @NonNull
    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public int supportsFormat(@NonNull Format format) {
        if (isCuesWithTiming(format) || subtitleDecoderFactory.supportsFormat(format)) {
            return RendererCapabilities.create(format.cryptoType == C.CRYPTO_TYPE_NONE ? C.FORMAT_HANDLED : C.FORMAT_UNSUPPORTED_DRM);
        }
        if (MimeTypes.isText(format.sampleMimeType)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
        }
        return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    }

    public void setFinalStreamEndPositionUs(long streamEndPositionUs) {
        Preconditions.checkState(isCurrentStreamFinal());
        this.finalStreamEndPositionUs = streamEndPositionUs;
    }

    @Override
    protected void onStreamChanged(@NonNull Format[] formats, long startPositionUs, long offsetUs,
                                   @NonNull MediaSource.MediaPeriodId mediaPeriodId) {
        this.streamFormat = formats[0];
        if (!isCuesWithTiming(this.streamFormat)) {
            assertLegacyDecodingEnabledIfRequired();
            if (this.subtitleDecoder != null) {
                this.decoderReplacementState = REPLACEMENT_STATE_SIGNAL_END_OF_STREAM;
            } else {
                initSubtitleDecoder();
            }
        } else {
            this.cuesResolver = new DelayedCuesResolver(this.streamFormat.cueReplacementBehavior);
        }
        this.lastRenderedCues = ImmutableList.of();
        clearOutput();
    }

    @Override
    protected void onPositionReset(long positionUs, boolean joining, boolean sampleStreamIsResetToKeyFrame) {
        this.lastRendererPositionUs = positionUs;
        if (this.cuesResolver != null) {
            this.cuesResolver.clear();
        }
        this.lastRenderedCues = ImmutableList.of();
        clearOutput();
        this.inputStreamEnded = false;
        this.outputStreamEnded = false;
        this.finalStreamEndPositionUs = C.TIME_UNSET;
        if (this.streamFormat != null && !isCuesWithTiming(this.streamFormat)) {
            if (this.decoderReplacementState != REPLACEMENT_STATE_NONE) {
                replaceSubtitleDecoder();
            } else {
                releaseSubtitleBuffers();
                SubtitleDecoder decoder = Preconditions.checkNotNull(this.subtitleDecoder);
                decoder.flush();
                decoder.setOutputStartTimeUs(getLastResetPositionUs());
            }
        }
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) {
        if (isCurrentStreamFinal() && finalStreamEndPositionUs != C.TIME_UNSET && positionUs >= finalStreamEndPositionUs) {
            releaseSubtitleBuffers();
            outputStreamEnded = true;
        }
        if (outputStreamEnded) {
            return;
        }

        this.lastRendererPositionUs = positionUs;

        if (streamFormat == null) {
            return;
        }

        if (isCuesWithTiming(streamFormat)) {
            renderFromCuesWithTiming(positionUs);
        } else {
            assertLegacyDecodingEnabledIfRequired();
            renderFromSubtitles(positionUs);
        }
    }

    @Override
    public void handleMessage(int messageType, @Nullable Object message) {
        if (messageType == MSG_SET_DELAY) {
            recalculateOutput();
        }
    }

    private void recalculateOutput() {
        if (streamFormat == null) return;

        if (isCuesWithTiming(streamFormat)) {
            if (cuesResolver == null) return;
            long effectivePositionUs = computeEffectivePositionUs(lastRendererPositionUs);
            ImmutableList<Cue> activeCues = cuesResolver.getCuesAtTimeUs(effectivePositionUs);
            lastRenderedCues = activeCues;
            long presentationTimeUs = getPresentationTimeUs(Math.max(0, effectivePositionUs));
            updateOutput(new CueGroup(activeCues, presentationTimeUs));
        } else if (subtitle != null) {
            long effectivePositionUs = computeEffectivePositionUs(lastRendererPositionUs);
            long presentationTimeUs = getPresentationTimeUs(getCurrentEventTimeUs(effectivePositionUs));
            CueGroup cueGroup = new CueGroup(subtitle.getCues(effectivePositionUs), presentationTimeUs);
            updateOutput(cueGroup);
        }
    }

    private void renderFromCuesWithTiming(long positionUs) {
        while (!inputStreamEnded) {
            int readResult = readSource(formatHolder, cueDecoderInputBuffer, 0);
            if (readResult == C.RESULT_BUFFER_READ) {
                if (cueDecoderInputBuffer.isEndOfStream()) {
                    inputStreamEnded = true;
                    break;
                }
                cueDecoderInputBuffer.flip();
                ByteBuffer cueData = Preconditions.checkNotNull(cueDecoderInputBuffer.data);
                CuesWithTiming cuesWithTiming = cueDecoder.decode(
                        cueDecoderInputBuffer.timeUs,
                        cueData.array(),
                        cueData.arrayOffset(),
                        cueData.limit()
                );
                cueDecoderInputBuffer.clear();
                if (cuesWithTiming != null && cuesResolver != null) {
                    cuesResolver.addCues(cuesWithTiming);
                }
            } else {
                break;
            }
        }

        if (cuesResolver == null) return;

        long effectivePositionUs = computeEffectivePositionUs(positionUs);
        ImmutableList<Cue> activeCues = cuesResolver.getCuesAtTimeUs(effectivePositionUs);

        if (!Objects.equals(activeCues, lastRenderedCues)) {
            lastRenderedCues = activeCues;
            long presentationTimeUs = getPresentationTimeUs(Math.max(0, effectivePositionUs));
            updateOutput(new CueGroup(activeCues, presentationTimeUs));
        }

        long pruneThresholdUs = OffsetSubtitleParserFactory.addSaturated(effectivePositionUs, -CUE_PRUNE_WINDOW_US);
        if (pruneThresholdUs > 0) {
            cuesResolver.discardCuesBeforeTimeUs(pruneThresholdUs);
        }
    }

    private void renderFromSubtitles(long positionUs) {
        long effectivePositionUs = computeEffectivePositionUs(positionUs);

        if (this.nextSubtitle == null) {
            Preconditions.checkNotNull(this.subtitleDecoder).setPositionUs(effectivePositionUs);
            try {
                this.nextSubtitle = this.subtitleDecoder.dequeueOutputBuffer();
            } catch (SubtitleDecoderException e) {
                handleDecoderError(e);
                return;
            }
        }
        if (getState() != STATE_STARTED) {
            return;
        }
        boolean textRendererNeedsUpdate = false;
        if (this.subtitle != null) {
            long subtitleNextEventTimeUs = getNextEventTime();
            while (subtitleNextEventTimeUs <= effectivePositionUs) {
                nextSubtitleEventIndex++;
                subtitleNextEventTimeUs = getNextEventTime();
                textRendererNeedsUpdate = true;
            }
        }
        if (this.nextSubtitle != null) {
            SubtitleOutputBuffer nextSub = this.nextSubtitle;
            if (nextSub.isEndOfStream()) {
                if (!textRendererNeedsUpdate && getNextEventTime() == Long.MAX_VALUE) {
                    if (decoderReplacementState == REPLACEMENT_STATE_WAIT_END_OF_STREAM) {
                        replaceSubtitleDecoder();
                    } else {
                        releaseSubtitleBuffers();
                        outputStreamEnded = true;
                    }
                }
            } else if (nextSub.timeUs <= effectivePositionUs) {
                if (this.subtitle != null) {
                    this.subtitle.release();
                }
                this.nextSubtitleEventIndex = nextSub.getNextEventTimeIndex(effectivePositionUs);
                this.subtitle = nextSub;
                this.nextSubtitle = null;
                textRendererNeedsUpdate = true;
            }
        }
        if (textRendererNeedsUpdate) {
            Preconditions.checkNotNull(this.subtitle);
            long presentationTimeUs = getPresentationTimeUs(getCurrentEventTimeUs(effectivePositionUs));
            CueGroup cueGroup = new CueGroup(this.subtitle.getCues(effectivePositionUs), presentationTimeUs);
            updateOutput(cueGroup);
        }
        if (decoderReplacementState == REPLACEMENT_STATE_WAIT_END_OF_STREAM) {
            return;
        }
        try {
            while (!inputStreamEnded) {
                SubtitleInputBuffer nextInputBuffer = this.nextSubtitleInputBuffer;
                if (nextInputBuffer == null) {
                    nextInputBuffer = Preconditions.checkNotNull(this.subtitleDecoder).dequeueInputBuffer();
                    if (nextInputBuffer == null) {
                        return;
                    }
                    this.nextSubtitleInputBuffer = nextInputBuffer;
                }
                if (decoderReplacementState == REPLACEMENT_STATE_SIGNAL_END_OF_STREAM) {
                    nextInputBuffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM);
                    Preconditions.checkNotNull(this.subtitleDecoder).queueInputBuffer(nextInputBuffer);
                    this.nextSubtitleInputBuffer = null;
                    decoderReplacementState = REPLACEMENT_STATE_WAIT_END_OF_STREAM;
                    return;
                }
                int result = readSource(formatHolder, nextInputBuffer, 0);
                if (result == C.RESULT_BUFFER_READ) {
                    if (nextInputBuffer.isEndOfStream()) {
                        inputStreamEnded = true;
                        waitingForKeyFrame = false;
                    } else {
                        Format format = formatHolder.format;
                        if (format == null) {
                            return;
                        }
                        nextInputBuffer.subsampleOffsetUs = format.subsampleOffsetUs;
                        nextInputBuffer.flip();
                        waitingForKeyFrame &= !nextInputBuffer.isKeyFrame();
                    }
                    if (waitingForKeyFrame) continue;
                    Preconditions.checkNotNull(this.subtitleDecoder).queueInputBuffer(nextInputBuffer);
                    this.nextSubtitleInputBuffer = null;
                    continue;
                }
                if (result != C.RESULT_NOTHING_READ) continue;
                return;
            }
        } catch (SubtitleDecoderException e) {
            handleDecoderError(e);
        }
    }

    private long computeEffectivePositionUs(long positionUs) {
        if (positionUs == C.TIME_UNSET) return C.TIME_UNSET;
        long delayUs = OffsetSubtitleParserFactory.millisToMicrosSaturated(subtitleDelayMs.get());
        return Math.max(-1L, OffsetSubtitleParserFactory.subtractSaturated(positionUs, delayUs));
    }

    @Override
    protected void onDisabled() {
        this.streamFormat = null;
        this.finalStreamEndPositionUs = C.TIME_UNSET;
        clearOutput();
        this.lastRendererPositionUs = C.TIME_UNSET;
        if (this.cuesResolver != null) {
            this.cuesResolver.clear();
        }
        if (this.subtitleDecoder != null) {
            releaseSubtitleDecoder();
        }
    }

    @Override
    public boolean isEnded() {
        return outputStreamEnded;
    }

    @Override
    public boolean isReady() {
        if (this.streamFormat == null) {
            return true;
        }
        if (isCuesWithTiming(this.streamFormat)) {
            return true;
        }
        return !outputStreamEnded && (!inputStreamEnded || hasEventsAfter(subtitle, lastRendererPositionUs)
                || hasEventsAfter(nextSubtitle, lastRendererPositionUs) || nextSubtitleInputBuffer == null);
    }

    private static boolean hasEventsAfter(@Nullable Subtitle sub, long timeUs) {
        return sub != null && sub.getEventTimeCount() > 0 && sub.getEventTime(sub.getEventTimeCount() - 1) > timeUs;
    }

    private void releaseSubtitleBuffers() {
        nextSubtitleInputBuffer = null;
        nextSubtitleEventIndex = -1;
        if (subtitle != null) {
            subtitle.release();
            subtitle = null;
        }
        if (nextSubtitle != null) {
            nextSubtitle.release();
            nextSubtitle = null;
        }
    }

    private void releaseSubtitleDecoder() {
        releaseSubtitleBuffers();
        Preconditions.checkNotNull(subtitleDecoder).release();
        subtitleDecoder = null;
        decoderReplacementState = REPLACEMENT_STATE_NONE;
    }

    private void initSubtitleDecoder() {
        waitingForKeyFrame = true;
        subtitleDecoder = subtitleDecoderFactory.createDecoder(Preconditions.checkNotNull(streamFormat));
        subtitleDecoder.setOutputStartTimeUs(getLastResetPositionUs());
    }

    private void replaceSubtitleDecoder() {
        releaseSubtitleDecoder();
        initSubtitleDecoder();
    }

    private long getNextEventTime() {
        if (nextSubtitleEventIndex == -1) {
            return Long.MAX_VALUE;
        }
        Preconditions.checkNotNull(subtitle);
        return nextSubtitleEventIndex >= subtitle.getEventTimeCount() ? Long.MAX_VALUE : subtitle.getEventTime(nextSubtitleEventIndex);
    }

    private void updateOutput(CueGroup cueGroup) {
        if (outputHandler != null) {
            outputHandler.obtainMessage(MSG_UPDATE_OUTPUT, cueGroup).sendToTarget();
        } else {
            invokeUpdateOutputInternal(cueGroup);
        }
    }

    private void clearOutput() {
        long effectivePos = computeEffectivePositionUs(lastRendererPositionUs);
        updateOutput(new CueGroup(ImmutableList.of(), getPresentationTimeUs(Math.max(0, effectivePos))));
    }

    @Override
    public boolean handleMessage(@NonNull Message msg) {
        if (msg.what == MSG_UPDATE_OUTPUT) {
            invokeUpdateOutputInternal((CueGroup) msg.obj);
            return true;
        }
        throw new IllegalStateException();
    }

    private void invokeUpdateOutputInternal(CueGroup cueGroup) {
        output.onCues(cueGroup.cues);
        output.onCues(cueGroup);
    }

    private void handleDecoderError(SubtitleDecoderException e) {
        Log.e(TAG, "Subtitle decoding failed. streamFormat=" + streamFormat, e);
        clearOutput();
        replaceSubtitleDecoder();
    }

    private long getCurrentEventTimeUs(long positionUs) {
        Preconditions.checkNotNull(subtitle);
        int nextEventIndex = subtitle.getNextEventTimeIndex(positionUs);
        if (nextEventIndex == 0 || subtitle.getEventTimeCount() == 0) {
            return subtitle.timeUs;
        }
        return nextEventIndex == -1 ? subtitle.getEventTime(subtitle.getEventTimeCount() - 1) : subtitle.getEventTime(nextEventIndex - 1);
    }

    private long getPresentationTimeUs(long positionUs) {
        Preconditions.checkState(positionUs != C.TIME_UNSET);
        return positionUs - getStreamOffsetUs();
    }

    private void assertLegacyDecodingEnabledIfRequired() {
        Preconditions.checkState(
                legacyDecodingEnabled
                        || Objects.equals(streamFormat.sampleMimeType, MimeTypes.APPLICATION_CEA608)
                        || Objects.equals(streamFormat.sampleMimeType, MimeTypes.APPLICATION_MP4CEA608)
                        || Objects.equals(streamFormat.sampleMimeType, MimeTypes.APPLICATION_CEA708),
                "Legacy decoding is disabled, can't handle %s samples (expected %s).",
                streamFormat.sampleMimeType,
                MimeTypes.APPLICATION_MEDIA3_CUES
        );
    }

    private static boolean isCuesWithTiming(Format format) {
        return Objects.equals(format.sampleMimeType, MimeTypes.APPLICATION_MEDIA3_CUES);
    }

}

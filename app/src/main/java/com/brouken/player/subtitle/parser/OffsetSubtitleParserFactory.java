package com.brouken.player.subtitle.parser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.Consumer;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;

import java.util.concurrent.atomic.AtomicInteger;

public final class OffsetSubtitleParserFactory implements SubtitleParser.Factory {

    private final SubtitleParser.Factory delegate;
    private final AtomicInteger delayMs;

    public OffsetSubtitleParserFactory(SubtitleParser.Factory delegate, AtomicInteger delayMs) {
        this.delegate = delegate;
        this.delayMs = delayMs;
    }

    public OffsetSubtitleParserFactory(SubtitleParser.Factory delegate, int delayMs) {
        this(delegate, new AtomicInteger(delayMs));
    }

    @Override
    public boolean supportsFormat(@NonNull Format format) {
        return delegate.supportsFormat(format);
    }

    @Override
    public int getCueReplacementBehavior(@NonNull Format format) {
        return delegate.getCueReplacementBehavior(format);
    }

    @NonNull
    @Override
    public SubtitleParser create(@NonNull Format format) {
        SubtitleParser parser = delegate.create(format);
        return new OffsetSubtitleParser(parser, delayMs);
    }

    public static long addSaturated(long a, long b) {
        long result = a + b;
        if (((a ^ result) & (b ^ result)) < 0) {
            return a > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return result;
    }

    public static long subtractSaturated(long a, long b) {
        if (b == Long.MIN_VALUE) {
            return a >= 0 ? Long.MAX_VALUE : a - Long.MIN_VALUE;
        }
        return addSaturated(a, -b);
    }

    public static long millisToMicrosSaturated(long millis) {
        if (millis > Long.MAX_VALUE / 1000L) return Long.MAX_VALUE;
        if (millis < Long.MIN_VALUE / 1000L) return Long.MIN_VALUE;
        return millis * 1000L;
    }

    public static long millisToMicrosSaturated(int millis) {
        return millisToMicrosSaturated((long) millis);
    }

    public static SubtitleParser.OutputOptions adjustOutputOptions(SubtitleParser.OutputOptions outputOptions, long delayUs) {
        if (outputOptions.startTimeUs == C.TIME_UNSET) return outputOptions;

        long adjustedStartUs = subtractSaturated(outputOptions.startTimeUs, delayUs);
        if (adjustedStartUs < 0) adjustedStartUs = 0;

        if (outputOptions.outputAllCues) {
            return SubtitleParser.OutputOptions.cuesAfterThenRemainingCuesBefore(adjustedStartUs);
        } else {
            return SubtitleParser.OutputOptions.onlyCuesAfter(adjustedStartUs);
        }
    }

    @Nullable
    public static CuesWithTiming shiftCues(CuesWithTiming cuesWithTiming, long delayUs) {
        if (delayUs == 0) return cuesWithTiming;

        long startTimeUs = cuesWithTiming.startTimeUs;
        if (startTimeUs == C.TIME_UNSET) return cuesWithTiming;

        long durationUs = cuesWithTiming.durationUs;
        if (durationUs != C.TIME_UNSET && durationUs < 0) {
            return null;
        }

        long shiftedStartUs = addSaturated(startTimeUs, delayUs);

        if (durationUs == C.TIME_UNSET) {
            if (shiftedStartUs < 0) shiftedStartUs = 0;

            if (shiftedStartUs == startTimeUs) {
                return cuesWithTiming;
            } else {
                return new CuesWithTiming(cuesWithTiming.cues, shiftedStartUs, durationUs);
            }
        }

        long endTimeUs = addSaturated(startTimeUs, durationUs);
        long shiftedEndUs = addSaturated(endTimeUs, delayUs);

        if (shiftedEndUs <= 0) return null;

        if (shiftedStartUs < 0) shiftedStartUs = 0;
        long shiftedDurationUs = shiftedEndUs - shiftedStartUs;

        if (shiftedDurationUs > 0) {
            return new CuesWithTiming(cuesWithTiming.cues, shiftedStartUs, shiftedDurationUs);
        } else {
            return null;
        }
    }

    private static final class OffsetSubtitleParser implements SubtitleParser {

        private final SubtitleParser delegate;
        private final AtomicInteger delayMs;

        private OffsetSubtitleParser(SubtitleParser delegate, AtomicInteger delayMs) {
            this.delegate = delegate;
            this.delayMs = delayMs;
        }

        @Override
        public void parse(@NonNull byte[] data, int offset, int length, @NonNull OutputOptions outputOptions, @NonNull Consumer<CuesWithTiming> output) {
            long delayUs = millisToMicrosSaturated(delayMs.get());
            if (delayUs == 0) {
                delegate.parse(data, offset, length, outputOptions, output);
            } else {
                OutputOptions adjustedOptions = adjustOutputOptions(outputOptions, delayUs);
                delegate.parse(data, offset, length, adjustedOptions, cuesWithTiming -> {
                    CuesWithTiming shifted = shiftCues(cuesWithTiming, delayUs);
                    if (shifted != null) output.accept(shifted);
                });
            }
        }

        @Override
        public void reset() {
            delegate.reset();
        }

        @Override
        public int getCueReplacementBehavior() {
            return delegate.getCueReplacementBehavior();
        }

    }

}

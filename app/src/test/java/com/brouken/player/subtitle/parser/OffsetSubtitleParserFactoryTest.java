package com.brouken.player.subtitle.parser;

import androidx.media3.common.C;
import androidx.media3.common.text.Cue;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;

import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OffsetSubtitleParserFactoryTest {

    private static Cue createDummyCue(String text) {
        return new Cue.Builder().setText(text).build();
    }

    private static CuesWithTiming createCue(long startUs, long durationUs) {
        return new CuesWithTiming(ImmutableList.of(createDummyCue("Test")), startUs, durationUs);
    }

    @Test
    public void testZeroDelay() {
        CuesWithTiming cue = createCue(1_000_000L, 2_000_000L);
        CuesWithTiming shifted = OffsetSubtitleParserFactory.shiftCues(cue, 0L);
        assertNotNull(shifted);
        assertEquals(1_000_000L, shifted.startTimeUs);
        assertEquals(2_000_000L, shifted.durationUs);
        assertEquals(3_000_000L, shifted.endTimeUs);
    }

    @Test
    public void testPositiveDelay() {
        CuesWithTiming cue = createCue(1_000_000L, 2_000_000L);
        CuesWithTiming shifted = OffsetSubtitleParserFactory.shiftCues(cue, 500_000L);
        assertNotNull(shifted);
        assertEquals(1_500_000L, shifted.startTimeUs);
        assertEquals(2_000_000L, shifted.durationUs);
        assertEquals(3_500_000L, shifted.endTimeUs);
    }

    @Test
    public void testNegativeDelay() {
        CuesWithTiming cue = createCue(2_000_000L, 2_000_000L);
        CuesWithTiming shifted = OffsetSubtitleParserFactory.shiftCues(cue, -500_000L);
        assertNotNull(shifted);
        assertEquals(1_500_000L, shifted.startTimeUs);
        assertEquals(2_000_000L, shifted.durationUs);
        assertEquals(3_500_000L, shifted.endTimeUs);
    }

    @Test
    public void testCueEntirelyBeforeTimeZero() {
        // Cue starts at 1s, duration 1s (ends at 2s). Delay is -3s.
        // Shifted end is 2s - 3s = -1s <= 0. Cue should be discarded (null).
        CuesWithTiming cue = createCue(1_000_000L, 1_000_000L);
        CuesWithTiming shifted = OffsetSubtitleParserFactory.shiftCues(cue, -3_000_000L);
        assertNull(shifted);
    }

    @Test
    public void testCueCrossingTimeZero() {
        // Cue starts at 1s, duration 3s (ends at 4s). Delay is -2s.
        // Shifted start is 1s - 2s = -1s (< 0 -> clamped to 0).
        // Shifted end is 4s - 2s = 2s (> 0).
        // Duration should be 2s - 0s = 2s.
        CuesWithTiming cue = createCue(1_000_000L, 3_000_000L);
        CuesWithTiming shifted = OffsetSubtitleParserFactory.shiftCues(cue, -2_000_000L);
        assertNotNull(shifted);
        assertEquals(0L, shifted.startTimeUs);
        assertEquals(2_000_000L, shifted.durationUs);
        assertEquals(2_000_000L, shifted.endTimeUs);
    }

    @Test
    public void testUnknownCueTimestamp() {
        CuesWithTiming cue = new CuesWithTiming(ImmutableList.of(createDummyCue("Test")), C.TIME_UNSET, 2_000_000L);
        CuesWithTiming shifted = OffsetSubtitleParserFactory.shiftCues(cue, 500_000L);
        assertNotNull(shifted);
        assertEquals(C.TIME_UNSET, shifted.startTimeUs);
    }

    @Test
    public void testUnknownCueDuration() {
        CuesWithTiming cue = new CuesWithTiming(ImmutableList.of(createDummyCue("Test")), 1_000_000L, C.TIME_UNSET);
        CuesWithTiming shifted = OffsetSubtitleParserFactory.shiftCues(cue, 500_000L);
        assertNotNull(shifted);
        assertEquals(1_500_000L, shifted.startTimeUs);
        assertEquals(C.TIME_UNSET, shifted.durationUs);

        // Unknown duration shifted before 0 should clamp start to 0
        CuesWithTiming shiftedNeg = OffsetSubtitleParserFactory.shiftCues(cue, -2_000_000L);
        assertNotNull(shiftedNeg);
        assertEquals(0L, shiftedNeg.startTimeUs);
        assertEquals(C.TIME_UNSET, shiftedNeg.durationUs);
    }

    @Test
    public void testVeryLargeOffsetsAndOverflowProtection() {
        long largeTime = Long.MAX_VALUE - 1000L;
        long largeDelay = 50_000L;
        assertEquals(Long.MAX_VALUE, OffsetSubtitleParserFactory.addSaturated(largeTime, largeDelay));

        long smallTime = Long.MIN_VALUE + 1000L;
        long negDelay = -50_000L;
        assertEquals(Long.MIN_VALUE, OffsetSubtitleParserFactory.addSaturated(smallTime, negDelay));

        // Millis to micros saturated
        assertEquals(2_147_483_647_000L, OffsetSubtitleParserFactory.millisToMicrosSaturated(Integer.MAX_VALUE));
        assertEquals(-2_147_483_648_000L, OffsetSubtitleParserFactory.millisToMicrosSaturated(Integer.MIN_VALUE));
        assertEquals(Long.MAX_VALUE, OffsetSubtitleParserFactory.millisToMicrosSaturated(Long.MAX_VALUE));
        assertEquals(Long.MIN_VALUE, OffsetSubtitleParserFactory.millisToMicrosSaturated(Long.MIN_VALUE));
    }

    @Test
    public void testOutputOptionsAdjustment() {
        SubtitleParser.OutputOptions options = SubtitleParser.OutputOptions.onlyCuesAfter(3_000_000L);
        SubtitleParser.OutputOptions adjusted = OffsetSubtitleParserFactory.adjustOutputOptions(options, 1_000_000L);
        assertEquals(2_000_000L, adjusted.startTimeUs);

        SubtitleParser.OutputOptions adjustedNegative = OffsetSubtitleParserFactory.adjustOutputOptions(options, -1_000_000L);
        assertEquals(4_000_000L, adjustedNegative.startTimeUs);

        SubtitleParser.OutputOptions adjustedLargeDelay = OffsetSubtitleParserFactory.adjustOutputOptions(options, 5_000_000L);
        assertEquals(0L, adjustedLargeDelay.startTimeUs);
    }

    @Test
    public void testRepeatedDelayChanges() {
        AtomicInteger delayMs = new AtomicInteger(0);
        CuesWithTiming cue = createCue(5_000_000L, 2_000_000L);

        delayMs.set(200);
        CuesWithTiming shifted1 = OffsetSubtitleParserFactory.shiftCues(cue, OffsetSubtitleParserFactory.millisToMicrosSaturated(delayMs.get()));
        assertEquals(5_200_000L, shifted1.startTimeUs);

        delayMs.set(400);
        CuesWithTiming shifted2 = OffsetSubtitleParserFactory.shiftCues(cue, OffsetSubtitleParserFactory.millisToMicrosSaturated(delayMs.get()));
        assertEquals(5_400_000L, shifted2.startTimeUs);

        delayMs.set(-200);
        CuesWithTiming shifted3 = OffsetSubtitleParserFactory.shiftCues(cue, OffsetSubtitleParserFactory.millisToMicrosSaturated(delayMs.get()));
        assertEquals(4_800_000L, shifted3.startTimeUs);
    }

}

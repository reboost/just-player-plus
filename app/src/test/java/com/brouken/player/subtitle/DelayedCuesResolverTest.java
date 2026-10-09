package com.brouken.player.subtitle;

import androidx.media3.common.Format;
import androidx.media3.common.text.Cue;
import androidx.media3.extractor.text.CuesWithTiming;

import com.google.common.collect.ImmutableList;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DelayedCuesResolverTest {

    private static Cue createCue(String text) {
        return new Cue.Builder().setText(text).build();
    }

    private static CuesWithTiming createTimingCue(String text, long startUs, long durationUs) {
        return new CuesWithTiming(ImmutableList.of(createCue(text)), startUs, durationUs);
    }

    @Test
    public void testEmptyResolverReturnsEmptyList() {
        DelayedCuesResolver resolver = new DelayedCuesResolver(Format.CUE_REPLACEMENT_BEHAVIOR_MERGE);
        ImmutableList<Cue> cues = resolver.getCuesAtTimeUs(1_000_000L);
        assertNotNull(cues);
        assertTrue(cues.isEmpty());
    }

    @Test
    public void testNegativeEffectiveTimeReturnsEmpty() {
        DelayedCuesResolver resolver = new DelayedCuesResolver(Format.CUE_REPLACEMENT_BEHAVIOR_MERGE);
        resolver.addCues(createTimingCue("Hello", 0L, 2_000_000L));
        ImmutableList<Cue> cues = resolver.getCuesAtTimeUs(-500_000L);
        assertTrue(cues.isEmpty());
    }

    @Test
    public void testMergeBehaviorOverlappingCues() {
        DelayedCuesResolver resolver = new DelayedCuesResolver(Format.CUE_REPLACEMENT_BEHAVIOR_MERGE);
        resolver.addCues(createTimingCue("Cue 1", 1_000_000L, 3_000_000L)); // 1s to 4s
        resolver.addCues(createTimingCue("Cue 2", 2_000_000L, 3_000_000L)); // 2s to 5s

        // At 1.5s: only Cue 1 is active
        ImmutableList<Cue> cuesAt1_5 = resolver.getCuesAtTimeUs(1_500_000L);
        assertEquals(1, cuesAt1_5.size());
        assertEquals("Cue 1", cuesAt1_5.get(0).text.toString());

        // At 2.5s: both Cue 1 and Cue 2 are active
        ImmutableList<Cue> cuesAt2_5 = resolver.getCuesAtTimeUs(2_500_000L);
        assertEquals(2, cuesAt2_5.size());

        // At 4.5s: only Cue 2 is active
        ImmutableList<Cue> cuesAt4_5 = resolver.getCuesAtTimeUs(4_500_000L);
        assertEquals(1, cuesAt4_5.size());
        assertEquals("Cue 2", cuesAt4_5.get(0).text.toString());

        // At 5.5s: neither is active
        ImmutableList<Cue> cuesAt5_5 = resolver.getCuesAtTimeUs(5_500_000L);
        assertTrue(cuesAt5_5.isEmpty());
    }

    @Test
    public void testReplaceBehaviorOverlappingCues() {
        DelayedCuesResolver resolver = new DelayedCuesResolver(Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE);
        resolver.addCues(createTimingCue("Cue 1", 1_000_000L, 4_000_000L)); // 1s to 5s
        resolver.addCues(createTimingCue("Cue 2", 2_000_000L, 2_000_000L)); // 2s to 4s

        // At 1.5s: Cue 1 active
        ImmutableList<Cue> cuesAt1_5 = resolver.getCuesAtTimeUs(1_500_000L);
        assertEquals(1, cuesAt1_5.size());
        assertEquals("Cue 1", cuesAt1_5.get(0).text.toString());

        // At 2.5s: Cue 2 replaces Cue 1
        ImmutableList<Cue> cuesAt2_5 = resolver.getCuesAtTimeUs(2_500_000L);
        assertEquals(1, cuesAt2_5.size());
        assertEquals("Cue 2", cuesAt2_5.get(0).text.toString());

        // At 4.5s: Cue 2 ended, no cue active
        ImmutableList<Cue> cuesAt4_5 = resolver.getCuesAtTimeUs(4_500_000L);
        assertTrue(cuesAt4_5.isEmpty());
    }

    @Test
    public void testDynamicDelayQueries() {
        DelayedCuesResolver resolver = new DelayedCuesResolver(Format.CUE_REPLACEMENT_BEHAVIOR_MERGE);
        // Subtitle appears in media at 5s to 7s
        resolver.addCues(createTimingCue("Hello World", 5_000_000L, 2_000_000L));

        long currentVideoPositionUs = 5_000_000L; // 5.0 seconds

        // 1. With zero delay: effectiveTime = 5s -> subtitle is shown
        long delayUs0 = 0L;
        long effectiveTime0 = currentVideoPositionUs - delayUs0;
        ImmutableList<Cue> cues0 = resolver.getCuesAtTimeUs(effectiveTime0);
        assertEquals(1, cues0.size());

        // 2. User increases delay to +2s: subtitle should appear 2s later (at video time 7s)
        // At video time 5s, effectiveTime = 5s - 2s = 3s -> subtitle should NOT be shown yet
        long delayUsPlus2 = 2_000_000L;
        long effectiveTimePlus2 = currentVideoPositionUs - delayUsPlus2;
        ImmutableList<Cue> cuesPlus2At5s = resolver.getCuesAtTimeUs(effectiveTimePlus2);
        assertTrue(cuesPlus2At5s.isEmpty());

        // At video time 7s with delay +2s, effectiveTime = 7s - 2s = 5s -> subtitle is shown!
        long videoTime7s = 7_000_000L;
        long effectiveTimePlus2At7s = videoTime7s - delayUsPlus2;
        ImmutableList<Cue> cuesPlus2At7s = resolver.getCuesAtTimeUs(effectiveTimePlus2At7s);
        assertEquals(1, cuesPlus2At7s.size());
        assertEquals("Hello World", cuesPlus2At7s.get(0).text.toString());

        // 3. User sets negative delay -2s (appears 2s earlier, at video time 3s)
        // At video time 3s, effectiveTime = 3s - (-2s) = 5s -> subtitle is shown!
        long delayUsMinus2 = -2_000_000L;
        long videoTime3s = 3_000_000L;
        long effectiveTimeMinus2At3s = videoTime3s - delayUsMinus2;
        ImmutableList<Cue> cuesMinus2At3s = resolver.getCuesAtTimeUs(effectiveTimeMinus2At3s);
        assertEquals(1, cuesMinus2At3s.size());
        assertEquals("Hello World", cuesMinus2At3s.get(0).text.toString());
    }

    @Test
    public void testOutOrderCueInsertion() {
        DelayedCuesResolver resolver = new DelayedCuesResolver(Format.CUE_REPLACEMENT_BEHAVIOR_MERGE);
        resolver.addCues(createTimingCue("Cue 3", 6_000_000L, 2_000_000L));
        resolver.addCues(createTimingCue("Cue 1", 2_000_000L, 2_000_000L));
        resolver.addCues(createTimingCue("Cue 2", 4_000_000L, 2_000_000L));

        assertEquals(3, resolver.getCuesCount());

        ImmutableList<Cue> cues1 = resolver.getCuesAtTimeUs(2_500_000L);
        assertEquals(1, cues1.size());
        assertEquals("Cue 1", cues1.get(0).text.toString());

        ImmutableList<Cue> cues2 = resolver.getCuesAtTimeUs(4_500_000L);
        assertEquals(1, cues2.size());
        assertEquals("Cue 2", cues2.get(0).text.toString());

        ImmutableList<Cue> cues3 = resolver.getCuesAtTimeUs(6_500_000L);
        assertEquals(1, cues3.size());
        assertEquals("Cue 3", cues3.get(0).text.toString());
    }

    @Test
    public void testDiscardCuesThreshold() {
        DelayedCuesResolver resolver = new DelayedCuesResolver(Format.CUE_REPLACEMENT_BEHAVIOR_MERGE);
        resolver.addCues(createTimingCue("Old Cue", 1_000_000L, 2_000_000L)); // ends at 3s
        resolver.addCues(createTimingCue("Active Cue", 10_000_000L, 2_000_000L)); // ends at 12s

        assertEquals(2, resolver.getCuesCount());

        // Discard before 5s
        resolver.discardCuesBeforeTimeUs(5_000_000L);
        assertEquals(1, resolver.getCuesCount());

        ImmutableList<Cue> cues = resolver.getCuesAtTimeUs(10_500_000L);
        assertEquals(1, cues.size());
        assertEquals("Active Cue", cues.get(0).text.toString());
    }

    @Test
    public void testClearOnSeek() {
        DelayedCuesResolver resolver = new DelayedCuesResolver(Format.CUE_REPLACEMENT_BEHAVIOR_MERGE);
        resolver.addCues(createTimingCue("Cue 1", 1_000_000L, 2_000_000L));
        assertEquals(1, resolver.getCuesCount());

        resolver.clear();
        assertEquals(0, resolver.getCuesCount());
        assertTrue(resolver.getCuesAtTimeUs(1_500_000L).isEmpty());
    }

}

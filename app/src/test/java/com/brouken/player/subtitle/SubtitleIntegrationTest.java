package com.brouken.player.subtitle;

import android.net.FakeUri;
import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.text.Cue;
import androidx.media3.extractor.text.CuesWithTiming;

import com.brouken.player.subtitle.parser.OffsetSubtitleParserFactory;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SubtitleIntegrationTest {

    private static Cue createCue(String text) {
        return new Cue.Builder().setText(text).build();
    }

    private static CuesWithTiming createTimingCue(String text, long startUs, long durationUs) {
        return new CuesWithTiming(ImmutableList.of(createCue(text)), startUs, durationUs);
    }

    @Test
    public void testExternalSubtitleConfigurationPreservesMetadata() {
        // Verify building a subtitle configuration preserves all necessary fields
        Uri uri = new FakeUri("file:///sdcard/Download/test.srt");
        MediaItem.SubtitleConfiguration config = new MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLanguage("eng")
                .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                .setLabel("English (External)")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build();

        assertEquals(uri, config.uri);
        assertEquals(MimeTypes.APPLICATION_SUBRIP, config.mimeType);
        assertEquals("eng", config.language);
        assertEquals(C.ROLE_FLAG_SUBTITLE, config.roleFlags);
        assertEquals("English (External)", config.label);
        assertEquals(C.SELECTION_FLAG_DEFAULT, config.selectionFlags);
    }

    @Test
    public void testSingleApplicationOfOffset() {
        // Ensure that when OffsetSubtitleParserFactory has 0 delay, it does not shift cues,
        // and DelayedCuesResolver applies the delay exactly once.
        AtomicInteger parserDelayMs = new AtomicInteger(0);
        CuesWithTiming originalCue = createTimingCue("Hello", 2_000_000L, 2_000_000L); // 2s to 4s

        // 1. Parser pass: shifted with 0 delay -> identical timing
        CuesWithTiming parsedCue = OffsetSubtitleParserFactory.shiftCues(originalCue,
                OffsetSubtitleParserFactory.millisToMicrosSaturated(parserDelayMs.get()));
        assertNotNull(parsedCue);
        assertEquals(originalCue.startTimeUs, parsedCue.startTimeUs);
        assertEquals(originalCue.durationUs, parsedCue.durationUs);

        // 2. DelayedCuesResolver pass with +1000ms delay:
        DelayedCuesResolver resolver = new DelayedCuesResolver(Format.CUE_REPLACEMENT_BEHAVIOR_MERGE);
        resolver.addCues(parsedCue);

        long delayUs = 1_000_000L; // +1s delay
        // At video position 2.5s, effective time = 2.5s - 1s = 1.5s -> cue (starts at 2s) is NOT shown
        assertTrue(resolver.getCuesAtTimeUs(2_500_000L - delayUs).isEmpty());

        // At video position 3.5s, effective time = 3.5s - 1s = 2.5s -> cue is shown exactly once
        ImmutableList<Cue> cues = resolver.getCuesAtTimeUs(3_500_000L - delayUs);
        assertEquals(1, cues.size());
        assertEquals("Hello", cues.get(0).text.toString());
    }

    @Test
    public void testEmbeddedSubtitleDynamicTimingResponse() {
        // Simulate container demuxing samples ahead into DelayedCuesResolver
        DelayedCuesResolver resolver = new DelayedCuesResolver(Format.CUE_REPLACEMENT_BEHAVIOR_MERGE);
        resolver.addCues(createTimingCue("Cue 1", 1_000_000L, 2_000_000L)); // 1s - 3s
        resolver.addCues(createTimingCue("Cue 2", 4_000_000L, 2_000_000L)); // 4s - 6s
        resolver.addCues(createTimingCue("Cue 3", 7_000_000L, 2_000_000L)); // 7s - 9s

        long currentPlaybackPositionUs = 4_500_000L; // 4.5s

        // Delay = 0s -> Cue 2 is visible
        long delayUs = 0L;
        ImmutableList<Cue> cues = resolver.getCuesAtTimeUs(currentPlaybackPositionUs - delayUs);
        assertEquals(1, cues.size());
        assertEquals("Cue 2", cues.get(0).text.toString());

        // Dynamic adjustment to +2s delay -> effective position becomes 4.5s - 2s = 2.5s -> Cue 1 becomes visible!
        delayUs = 2_000_000L;
        cues = resolver.getCuesAtTimeUs(currentPlaybackPositionUs - delayUs);
        assertEquals(1, cues.size());
        assertEquals("Cue 1", cues.get(0).text.toString());

        // Dynamic adjustment to -3s delay -> effective position becomes 4.5s - (-3s) = 7.5s -> Cue 3 becomes visible!
        delayUs = -3_000_000L;
        cues = resolver.getCuesAtTimeUs(currentPlaybackPositionUs - delayUs);
        assertEquals(1, cues.size());
        assertEquals("Cue 3", cues.get(0).text.toString());
    }

}

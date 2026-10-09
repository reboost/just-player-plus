package com.brouken.player.subtitle;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.text.TextOutput;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DelayedTextRendererTest {

    private static class TestTextOutput implements TextOutput {
        CueGroup lastCueGroup;

        @Override
        public void onCues(List<androidx.media3.common.text.Cue> cues) {
        }

        @Override
        public void onCues(CueGroup cueGroup) {
            this.lastCueGroup = cueGroup;
        }
    }

    @Test
    public void testSupportsMedia3CuesFormat() {
        TestTextOutput textOutput = new TestTextOutput();
        AtomicInteger delayMs = new AtomicInteger(0);
        DelayedTextRenderer renderer = new DelayedTextRenderer(textOutput, null, delayMs);

        Format cuesFormat = new Format.Builder()
                .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                .build();

        int support = renderer.supportsFormat(cuesFormat);
        assertEquals(C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(support));
    }

    @Test
    public void testSupportsSubripFormat() {
        TestTextOutput textOutput = new TestTextOutput();
        AtomicInteger delayMs = new AtomicInteger(0);
        DelayedTextRenderer renderer = new DelayedTextRenderer(textOutput, null, delayMs);

        Format srtFormat = new Format.Builder()
                .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
                .build();

        int support = renderer.supportsFormat(srtFormat);
        assertEquals(C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(support));
    }

    @Test
    public void testSupportsWebVttFormat() {
        TestTextOutput textOutput = new TestTextOutput();
        AtomicInteger delayMs = new AtomicInteger(0);
        DelayedTextRenderer renderer = new DelayedTextRenderer(textOutput, null, delayMs);

        Format vttFormat = new Format.Builder()
                .setSampleMimeType(MimeTypes.TEXT_VTT)
                .build();

        int support = renderer.supportsFormat(vttFormat);
        assertEquals(C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(support));
    }

    @Test
    public void testSupportsSsaFormat() {
        TestTextOutput textOutput = new TestTextOutput();
        AtomicInteger delayMs = new AtomicInteger(0);
        DelayedTextRenderer renderer = new DelayedTextRenderer(textOutput, null, delayMs);

        Format ssaFormat = new Format.Builder()
                .setSampleMimeType(MimeTypes.TEXT_SSA)
                .build();

        int support = renderer.supportsFormat(ssaFormat);
        assertEquals(C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(support));
    }

    @Test
    public void testGetName() {
        TestTextOutput textOutput = new TestTextOutput();
        AtomicInteger delayMs = new AtomicInteger(0);
        DelayedTextRenderer renderer = new DelayedTextRenderer(textOutput, null, delayMs);
        assertEquals("DelayedTextRenderer", renderer.getName());
    }

    @Test
    public void testIsReadyAlwaysTrueForNonBlocking() {
        TestTextOutput textOutput = new TestTextOutput();
        AtomicInteger delayMs = new AtomicInteger(0);
        DelayedTextRenderer renderer = new DelayedTextRenderer(textOutput, null, delayMs);
        assertTrue(renderer.isReady());
    }

}

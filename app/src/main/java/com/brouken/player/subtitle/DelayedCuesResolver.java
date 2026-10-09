package com.brouken.player.subtitle;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.text.Cue;
import androidx.media3.extractor.text.CuesWithTiming;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;

public final class DelayedCuesResolver {

    private final int cueReplacementBehavior;
    private final List<CuesWithTiming> cuesWithTimingList = new ArrayList<>();

    public DelayedCuesResolver(int cueReplacementBehavior) {
        this.cueReplacementBehavior = cueReplacementBehavior;
    }

    public synchronized boolean addCues(CuesWithTiming cues) {
        if (cues == null || cues.startTimeUs == C.TIME_UNSET) {
            return false;
        }

        int insertIndex = cuesWithTimingList.size();
        for (int i = cuesWithTimingList.size() - 1; i >= 0; i--) {
            if (cues.startTimeUs >= cuesWithTimingList.get(i).startTimeUs) {
                insertIndex = i + 1;
                break;
            }
        }
        if (insertIndex == cuesWithTimingList.size() && !cuesWithTimingList.isEmpty()
                && cues.startTimeUs < cuesWithTimingList.get(0).startTimeUs) {
            insertIndex = 0;
        }

        cuesWithTimingList.add(insertIndex, cues);
        return true;
    }

    public synchronized ImmutableList<Cue> getCuesAtTimeUs(long effectiveTimeUs) {
        if (effectiveTimeUs < 0 || cuesWithTimingList.isEmpty()) {
            return ImmutableList.of();
        }

        if (cueReplacementBehavior == Format.CUE_REPLACEMENT_BEHAVIOR_MERGE) {
            List<Cue> visibleCues = new ArrayList<>();
            for (int i = 0; i < cuesWithTimingList.size(); i++) {
                CuesWithTiming item = cuesWithTimingList.get(i);
                if (effectiveTimeUs >= item.startTimeUs) {
                    if (item.endTimeUs == C.TIME_UNSET || effectiveTimeUs < item.endTimeUs) {
                        visibleCues.addAll(item.cues);
                    }
                } else {
                    break;
                }
            }
            return ImmutableList.copyOf(visibleCues);
        } else {
            CuesWithTiming latestCandidate = null;
            for (int i = cuesWithTimingList.size() - 1; i >= 0; i--) {
                CuesWithTiming item = cuesWithTimingList.get(i);
                if (item.startTimeUs <= effectiveTimeUs) {
                    latestCandidate = item;
                    break;
                }
            }
            if (latestCandidate != null) {
                if (latestCandidate.endTimeUs == C.TIME_UNSET || effectiveTimeUs < latestCandidate.endTimeUs) {
                    return latestCandidate.cues;
                }
            }
            return ImmutableList.of();
        }
    }

    public synchronized void discardCuesBeforeTimeUs(long discardThresholdUs) {
        if (discardThresholdUs <= 0) return;

        for (int i = 0; i < cuesWithTimingList.size(); i++) {
            CuesWithTiming item = cuesWithTimingList.get(i);
            if (item.endTimeUs != C.TIME_UNSET && item.endTimeUs < discardThresholdUs) {
                cuesWithTimingList.remove(i);
                i--;
            } else if (item.startTimeUs > discardThresholdUs) {
                break;
            }
        }
    }

    public synchronized void clear() {
        cuesWithTimingList.clear();
    }

    public synchronized int getCuesCount() {
        return cuesWithTimingList.size();
    }

}

package com.nyx.ffmpeg.model;

public sealed interface SubtitleMode permits SubtitleMode.Extract, SubtitleMode.BurnIn {
    Extract Extract = SubtitleMode.Extract.INSTANCE;

    final class Extract implements SubtitleMode {
        static final Extract INSTANCE = new Extract();

        private Extract() {
        }

        @Override
        public String toString() {
            return "Extract";
        }
    }

    record BurnIn(int trackIndex) implements SubtitleMode {
        public int getTrackIndex() {
            return trackIndex;
        }
    }
}

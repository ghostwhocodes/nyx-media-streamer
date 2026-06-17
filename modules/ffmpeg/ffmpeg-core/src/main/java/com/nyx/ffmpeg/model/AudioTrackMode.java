package com.nyx.ffmpeg.model;

public sealed interface AudioTrackMode permits AudioTrackMode.All, AudioTrackMode.AllWithStereoDownmix, AudioTrackMode.Single {
    All All = AudioTrackMode.All.INSTANCE;
    AllWithStereoDownmix AllWithStereoDownmix = AudioTrackMode.AllWithStereoDownmix.INSTANCE;

    final class All implements AudioTrackMode {
        static final All INSTANCE = new All();

        private All() {
        }

        @Override
        public String toString() {
            return "All";
        }
    }

    final class AllWithStereoDownmix implements AudioTrackMode {
        static final AllWithStereoDownmix INSTANCE = new AllWithStereoDownmix();

        private AllWithStereoDownmix() {
        }

        @Override
        public String toString() {
            return "AllWithStereoDownmix";
        }
    }

    record Single(int index) implements AudioTrackMode {
        public int getIndex() {
            return index;
        }
    }
}

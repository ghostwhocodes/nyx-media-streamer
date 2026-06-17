package com.nyx.ffmpeg.model;

import java.util.Objects;

public sealed interface VideoCodec permits VideoCodec.Copy, VideoCodec.H264, VideoCodec.H265, VideoCodec.AV1Svt {
    Copy Copy = VideoCodec.Copy.INSTANCE;

    final class Copy implements VideoCodec {
        static final Copy INSTANCE = new Copy();

        private Copy() {
        }

        @Override
        public String toString() {
            return "Copy";
        }
    }

    record H264(
        H264Preset preset,
        int crf,
        H264Profile profile,
        Long maxRate,
        Long bufSize
    ) implements VideoCodec {
        public H264 {
            preset = Objects.requireNonNull(preset, "preset");
            profile = Objects.requireNonNull(profile, "profile");
        }

        public H264(H264Preset preset, int crf, H264Profile profile) {
            this(preset, crf, profile, null, null);
        }

        public H264Preset getPreset() {
            return preset;
        }

        public int getCrf() {
            return crf;
        }

        public H264Profile getProfile() {
            return profile;
        }

        public Long getMaxRate() {
            return maxRate;
        }

        public Long getBufSize() {
            return bufSize;
        }
    }

    record H265(
        H265Preset preset,
        int crf
    ) implements VideoCodec {
        public H265 {
            preset = Objects.requireNonNull(preset, "preset");
        }

        public H265Preset getPreset() {
            return preset;
        }

        public int getCrf() {
            return crf;
        }
    }

    record AV1Svt(
        int preset,
        int crf
    ) implements VideoCodec {
        public int getPreset() {
            return preset;
        }

        public int getCrf() {
            return crf;
        }
    }
}

package com.nyx.ffmpeg.model;

public sealed interface AudioCodec permits AudioCodec.Copy, AudioCodec.AAC, AudioCodec.Opus {
    Copy Copy = AudioCodec.Copy.INSTANCE;

    final class Copy implements AudioCodec {
        static final Copy INSTANCE = new Copy();

        private Copy() {
        }

        @Override
        public String toString() {
            return "Copy";
        }
    }

    record AAC(
        int bitrate,
        Integer channels
    ) implements AudioCodec {
        public AAC(int bitrate) {
            this(bitrate, null);
        }

        public int getBitrate() {
            return bitrate;
        }

        public Integer getChannels() {
            return channels;
        }
    }

    record Opus(
        int bitrate,
        Integer channels
    ) implements AudioCodec {
        public Opus(int bitrate) {
            this(bitrate, null);
        }

        public int getBitrate() {
            return bitrate;
        }

        public Integer getChannels() {
            return channels;
        }
    }
}

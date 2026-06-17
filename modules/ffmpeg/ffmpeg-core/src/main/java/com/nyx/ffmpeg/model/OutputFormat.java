package com.nyx.ffmpeg.model;

public sealed interface OutputFormat permits OutputFormat.Dash, OutputFormat.Hls, OutputFormat.HlsMpegTs, OutputFormat.Both, OutputFormat.Cmaf {
    Dash Dash = OutputFormat.Dash.INSTANCE;
    Hls Hls = OutputFormat.Hls.INSTANCE;
    HlsMpegTs HlsMpegTs = OutputFormat.HlsMpegTs.INSTANCE;
    Both Both = OutputFormat.Both.INSTANCE;
    Cmaf Cmaf = OutputFormat.Cmaf.INSTANCE;

    final class Dash implements OutputFormat {
        static final Dash INSTANCE = new Dash();

        private Dash() {
        }

        @Override
        public String toString() {
            return "Dash";
        }
    }

    final class Hls implements OutputFormat {
        static final Hls INSTANCE = new Hls();

        private Hls() {
        }

        @Override
        public String toString() {
            return "Hls";
        }
    }

    final class HlsMpegTs implements OutputFormat {
        static final HlsMpegTs INSTANCE = new HlsMpegTs();

        private HlsMpegTs() {
        }

        @Override
        public String toString() {
            return "HlsMpegTs";
        }
    }

    final class Both implements OutputFormat {
        static final Both INSTANCE = new Both();

        private Both() {
        }

        @Override
        public String toString() {
            return "Both";
        }
    }

    final class Cmaf implements OutputFormat {
        static final Cmaf INSTANCE = new Cmaf();

        private Cmaf() {
        }

        @Override
        public String toString() {
            return "Cmaf";
        }
    }
}

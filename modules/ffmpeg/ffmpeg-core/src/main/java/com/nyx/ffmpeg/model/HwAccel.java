package com.nyx.ffmpeg.model;

import java.util.Objects;

public sealed interface HwAccel permits HwAccel.Auto, HwAccel.None, HwAccel.Vaapi, HwAccel.Nvenc, HwAccel.Qsv {
    Auto Auto = HwAccel.Auto.INSTANCE;
    None None = HwAccel.None.INSTANCE;
    Nvenc Nvenc = HwAccel.Nvenc.INSTANCE;
    Qsv Qsv = HwAccel.Qsv.INSTANCE;

    final class Auto implements HwAccel {
        static final Auto INSTANCE = new Auto();

        private Auto() {
        }

        @Override
        public String toString() {
            return "Auto";
        }
    }

    final class None implements HwAccel {
        static final None INSTANCE = new None();

        private None() {
        }

        @Override
        public String toString() {
            return "None";
        }
    }

    record Vaapi(String device) implements HwAccel {
        public Vaapi {
            device = Objects.requireNonNull(device, "device");
        }

        public String getDevice() {
            return device;
        }
    }

    final class Nvenc implements HwAccel {
        static final Nvenc INSTANCE = new Nvenc();

        private Nvenc() {
        }

        @Override
        public String toString() {
            return "Nvenc";
        }
    }

    final class Qsv implements HwAccel {
        static final Qsv INSTANCE = new Qsv();

        private Qsv() {
        }

        @Override
        public String toString() {
            return "Qsv";
        }
    }
}

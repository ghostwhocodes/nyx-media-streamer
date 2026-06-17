package com.nyx.ffmpeg;

final class UncheckedThrow {

    private UncheckedThrow() {
    }

    static <T> T sneakyThrow(Throwable throwable) {
        UncheckedThrow.<RuntimeException>throwUnchecked(throwable);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable throwable) throws E {
        throw (E) throwable;
    }
}

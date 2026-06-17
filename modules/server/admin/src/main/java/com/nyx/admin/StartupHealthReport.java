package com.nyx.admin;

import java.util.List;

public record StartupHealthReport(
    String ffmpegVersion,
    String ffprobeVersion,
    List<String> hwAccels,
    List<String> encoders,
    List<MediaRootStatus> mediaRoots,
    String dbStatus,
    JvmInfo jvmInfo
) {
}

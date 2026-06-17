package com.nyx.transcode;

import com.nyx.ffmpeg.model.SegmentDuration;
import com.nyx.transcode.contracts.TranscodeRepresentation;
import java.util.List;

public final class ManifestGenerator {
    public String generateDashMpd(List<SegmentInfo> segments, boolean isComplete, double totalDurationSecs) {
        return generateDashMpd(
            segments,
            List.of(),
            List.of(),
            isComplete,
            totalDurationSecs,
            SegmentDuration.ADAPTIVE,
            List.of(),
            "",
            128_000
        );
    }

    public String generateDashMpd(
        List<SegmentInfo> segments,
        List<AudioTrackInfo> audioTracks,
        boolean isComplete,
        double totalDurationSecs
    ) {
        return generateDashMpd(
            segments,
            audioTracks,
            List.of(),
            isComplete,
            totalDurationSecs,
            SegmentDuration.ADAPTIVE,
            List.of(),
            "",
            128_000
        );
    }

    public String generateDashMpd(
        List<SegmentInfo> segments,
        List<AudioTrackInfo> audioTracks,
        List<SubtitleTrackInfo> subtitleTracks,
        boolean isComplete,
        double totalDurationSecs
    ) {
        return generateDashMpd(
            segments,
            audioTracks,
            subtitleTracks,
            isComplete,
            totalDurationSecs,
            SegmentDuration.ADAPTIVE,
            List.of(),
            "",
            128_000
        );
    }

    public String generateDashMpd(
        List<SegmentInfo> segments,
        boolean isComplete,
        double totalDurationSecs,
        List<TranscodeRepresentation> representations
    ) {
        return generateDashMpd(
            segments,
            List.of(),
            List.of(),
            isComplete,
            totalDurationSecs,
            SegmentDuration.ADAPTIVE,
            representations,
            "",
            128_000
        );
    }

    @SuppressWarnings("unused")
    public String generateDashMpd(
        List<SegmentInfo> segments,
        List<AudioTrackInfo> audioTracks,
        List<SubtitleTrackInfo> subtitleTracks,
        boolean isComplete,
        double totalDurationSecs,
        SegmentDuration segmentDuration,
        List<TranscodeRepresentation> representations,
        String jobId,
        int audioBandwidth
    ) {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>");

        String type = isComplete ? "static" : "dynamic";
        String durationAttr = isComplete
            ? "mediaPresentationDuration=\"PT" + (int) totalDurationSecs + "S\""
            : "minimumUpdatePeriod=\"PT2S\"";

        appendLine(
            builder,
            "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"" + type + "\" " + durationAttr
                + " profiles=\"urn:mpeg:dash:profile:isoff-live:2011\">"
        );
        appendLine(builder, "  <Period>");

        if (!representations.isEmpty()) {
            appendLine(builder, "    <AdaptationSet mimeType=\"video/mp4\" contentType=\"video\">");
            for (TranscodeRepresentation representation : representations) {
                String representationId = representation.getWidth() + "x" + representation.getHeight();
                List<SegmentInfo> representationSegments = segments.stream()
                    .filter(segment -> representationId.equals(segment.getRepresentationId()))
                    .toList();
                appendLine(
                    builder,
                    "      <Representation id=\"" + representationId + "\" width=\"" + representation.getWidth()
                        + "\" height=\"" + representation.getHeight() + "\" bandwidth=\""
                        + (representation.getBitrateKbps() * 1000) + "\">"
                );
                appendLine(builder, "        <SegmentList>");
                for (SegmentInfo segment : representationSegments) {
                    appendLine(builder, "          <SegmentURL media=\"" + segment.getName() + "\" />");
                }
                appendLine(builder, "        </SegmentList>");
                appendLine(builder, "      </Representation>");
            }
            appendLine(builder, "    </AdaptationSet>");
        } else {
            appendLine(builder, "    <AdaptationSet mimeType=\"video/mp4\" contentType=\"video\">");
            appendLine(builder, "      <Representation id=\"video\" bandwidth=\"0\">");
            appendLine(builder, "        <SegmentList>");
            segments.stream()
                .filter(segment -> "video".equals(segment.getRepresentationId()) || segment.getRepresentationId().isEmpty())
                .forEach(segment -> appendLine(builder, "          <SegmentURL media=\"" + segment.getName() + "\" />"));
            appendLine(builder, "        </SegmentList>");
            appendLine(builder, "      </Representation>");
            appendLine(builder, "    </AdaptationSet>");
        }

        if (!audioTracks.isEmpty()) {
            for (AudioTrackInfo track : audioTracks) {
                String language = track.getLanguage() == null ? "und" : track.getLanguage();
                String label = track.getTitle() == null ? "Track " + track.getTrackIndex() : track.getTitle();
                appendLine(
                    builder,
                    "    <AdaptationSet mimeType=\"audio/mp4\" contentType=\"audio\" lang=\"" + language
                        + "\" label=\"" + label + "\">"
                );
                appendLine(
                    builder,
                    "      <Representation id=\"audio_" + track.getTrackIndex() + "\" bandwidth=\""
                        + (track.getBitrateKbps() * 1000) + "\">"
                );
                appendLine(builder, "        <SegmentList>");
                segments.stream()
                    .filter(segment -> ("audio_" + track.getTrackIndex()).equals(segment.getRepresentationId()))
                    .forEach(segment -> appendLine(builder, "          <SegmentURL media=\"" + segment.getName() + "\" />"));
                appendLine(builder, "        </SegmentList>");
                appendLine(builder, "      </Representation>");

                if (track.hasDownmix()) {
                    appendLine(
                        builder,
                        "      <Representation id=\"audio_" + track.getTrackIndex() + "_stereo\" bandwidth=\""
                            + (track.getBitrateKbps() * 1000) + "\">"
                    );
                    appendLine(builder, "        <SegmentList>");
                    segments.stream()
                        .filter(segment -> ("audio_" + track.getTrackIndex() + "_stereo").equals(segment.getRepresentationId()))
                        .forEach(segment -> appendLine(builder, "          <SegmentURL media=\"" + segment.getName() + "\" />"));
                    appendLine(builder, "        </SegmentList>");
                    appendLine(builder, "      </Representation>");
                }
                appendLine(builder, "    </AdaptationSet>");
            }
        } else {
            appendLine(builder, "    <AdaptationSet mimeType=\"audio/mp4\" contentType=\"audio\">");
            appendLine(builder, "      <Representation id=\"audio\" bandwidth=\"" + audioBandwidth + "\">");
            appendLine(builder, "        <SegmentList>");
            segments.stream()
                .filter(segment -> "audio".equals(segment.getRepresentationId()))
                .forEach(segment -> appendLine(builder, "          <SegmentURL media=\"" + segment.getName() + "\" />"));
            appendLine(builder, "        </SegmentList>");
            appendLine(builder, "      </Representation>");
            appendLine(builder, "    </AdaptationSet>");
        }

        for (SubtitleTrackInfo track : subtitleTracks) {
            String language = track.getLanguage() == null ? "und" : track.getLanguage();
            String label = track.getTitle() == null ? "Track " + track.getTrackIndex() : track.getTitle();
            appendLine(
                builder,
                "    <AdaptationSet mimeType=\"text/vtt\" contentType=\"text\" lang=\"" + language
                    + "\" label=\"" + label + "\">"
            );
            appendLine(builder, "      <Representation id=\"sub_" + track.getTrackIndex() + "\" bandwidth=\"0\">");
            appendLine(
                builder,
                "        <BaseURL>/api/v1/transcode/jobs/" + jobId + "/subtitles/" + track.getTrackIndex() + "</BaseURL>"
            );
            appendLine(builder, "      </Representation>");
            appendLine(builder, "    </AdaptationSet>");
        }

        appendLine(builder, "  </Period>");
        appendLine(builder, "</MPD>");
        return builder.toString();
    }

    public String generateHlsMaster(List<HlsVariantStream> variantStreams, String jobId) {
        return generateHlsMaster(variantStreams, List.of(), List.of(), jobId);
    }

    public String generateHlsMaster(
        List<HlsVariantStream> variantStreams,
        List<AudioTrackInfo> audioTracks,
        List<SubtitleTrackInfo> subtitleTracks,
        String jobId
    ) {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "#EXTM3U");

        for (AudioTrackInfo track : audioTracks) {
            String language = track.getLanguage() == null ? "und" : track.getLanguage();
            String name = track.getTitle() == null ? "Track " + track.getTrackIndex() : track.getTitle();
            appendLine(
                builder,
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"" + name + "\",LANGUAGE=\"" + language
                    + "\",URI=\"audio_" + track.getTrackIndex() + ".m3u8\""
            );
            if (track.hasDownmix()) {
                appendLine(
                    builder,
                    "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"" + name + " (Stereo)\",LANGUAGE=\"" + language
                        + "\",URI=\"audio_" + track.getTrackIndex() + "_stereo.m3u8\""
                );
            }
        }

        for (SubtitleTrackInfo track : subtitleTracks) {
            String language = track.getLanguage() == null ? "und" : track.getLanguage();
            String name = track.getTitle() == null ? "Track " + track.getTrackIndex() : track.getTitle();
            String subtitleUri = "/api/v1/transcode/jobs/" + jobId + "/subtitles/" + track.getTrackIndex() + ".m3u8";
            appendLine(
                builder,
                "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"" + name + "\",LANGUAGE=\"" + language
                    + "\",URI=\"" + subtitleUri + "\""
            );
        }

        for (HlsVariantStream variant : variantStreams) {
            String resolution = variant.getWidth() != null && variant.getHeight() != null
                ? ",RESOLUTION=" + variant.getWidth() + "x" + variant.getHeight()
                : "";
            String audioGroup = variant.getAudioGroupId() != null ? ",AUDIO=\"" + variant.getAudioGroupId() + "\"" : "";
            String subtitlesGroup = variant.getSubtitleGroupId() != null
                ? ",SUBTITLES=\"" + variant.getSubtitleGroupId() + "\""
                : "";
            appendLine(
                builder,
                "#EXT-X-STREAM-INF:BANDWIDTH=" + variant.getBandwidth() + resolution + audioGroup + subtitlesGroup
            );
            appendLine(builder, variant.getUri());
        }

        return builder.toString();
    }

    public String generateHlsMediaPlaylist(List<SegmentInfo> segments, boolean isComplete, int targetDuration) {
        return generateHlsMediaPlaylist(segments, isComplete, targetDuration, "init.mp4", "");
    }

    public String generateHlsMediaPlaylist(
        List<SegmentInfo> segments,
        boolean isComplete,
        int targetDuration,
        String initSegmentName,
        String segmentPathPrefix
    ) {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "#EXTM3U");
        appendLine(builder, "#EXT-X-VERSION:7");
        appendLine(builder, "#EXT-X-TARGETDURATION:" + targetDuration);
        appendLine(builder, "#EXT-X-MEDIA-SEQUENCE:0");
        if (initSegmentName != null && !initSegmentName.isBlank()) {
            appendLine(builder, "#EXT-X-MAP:URI=\"" + segmentPathPrefix + initSegmentName + "\"");
        }

        for (SegmentInfo segment : segments) {
            appendLine(builder, "#EXTINF:" + segment.getDurationSecs() + ",");
            appendLine(builder, segmentPathPrefix + segment.getName());
        }

        if (isComplete) {
            appendLine(builder, "#EXT-X-ENDLIST");
        }
        return builder.toString();
    }

    public String generateHlsSubtitleMediaPlaylist(String jobId, int trackIndex) {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "#EXTM3U");
        appendLine(builder, "#EXT-X-VERSION:3");
        appendLine(builder, "#EXT-X-TARGETDURATION:99999");
        appendLine(builder, "#EXT-X-MEDIA-SEQUENCE:0");
        appendLine(builder, "#EXTINF:99999.0,");
        appendLine(builder, "/api/v1/transcode/jobs/" + jobId + "/subtitles/" + trackIndex);
        appendLine(builder, "#EXT-X-ENDLIST");
        return builder.toString();
    }

    private static void appendLine(StringBuilder builder, String line) {
        builder.append(line).append('\n');
    }
}

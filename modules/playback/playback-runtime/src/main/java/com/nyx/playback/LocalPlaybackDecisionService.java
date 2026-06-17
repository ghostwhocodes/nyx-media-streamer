package com.nyx.playback;

import static com.nyx.ffmpeg.MediaProberInterop.probeCachedOrThrow;

import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.ffmpeg.MediaProber;
import com.nyx.ffmpeg.model.AdaptiveProfile;
import com.nyx.ffmpeg.model.AudioCodec;
import com.nyx.ffmpeg.model.AudioStream;
import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.ffmpeg.model.ProbeStreams;
import com.nyx.ffmpeg.model.Profile;
import com.nyx.ffmpeg.model.SubtitleStream;
import com.nyx.ffmpeg.model.TranscodeProfiles;
import com.nyx.ffmpeg.model.VideoCodec;
import com.nyx.ffmpeg.model.VideoStream;
import com.nyx.playback.contracts.AudioConstraint;
import com.nyx.playback.contracts.AudioTrackSelectionMode;
import com.nyx.playback.contracts.PlaybackCapabilitySet;
import com.nyx.playback.contracts.PlaybackConstraints;
import com.nyx.playback.contracts.PlaybackDecision;
import com.nyx.playback.contracts.PlaybackDecisionService;
import com.nyx.playback.contracts.PlaybackMode;
import com.nyx.playback.contracts.PlaybackOutputSummary;
import com.nyx.playback.contracts.PlaybackReason;
import com.nyx.playback.contracts.PlaybackRequest;
import com.nyx.playback.contracts.PlaybackSourceAudioStream;
import com.nyx.playback.contracts.PlaybackSourceCharacteristics;
import com.nyx.playback.contracts.PlaybackSourceSubtitleStream;
import com.nyx.playback.contracts.PlaybackSourceVideoStream;
import com.nyx.playback.contracts.StreamDescriptor;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.stream.representation.contracts.StreamRepresentationConstraint;
import com.nyx.stream.representation.contracts.StreamRepresentationConstraintKind;
import com.nyx.stream.representation.contracts.StreamRepresentationPolicy;
import com.nyx.stream.representation.contracts.StreamRepresentationTraits;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import com.nyx.playback.contracts.SubtitleDelivery;
import com.nyx.playback.contracts.SubtitleSelectionMode;
import com.nyx.playback.contracts.VideoConstraint;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LocalPlaybackDecisionService implements PlaybackDecisionService {
    private static final String DEFAULT_TRANSCODE_PROFILE = "h264_fast";
    private static final StreamRepresentationPolicy REPRESENTATION_POLICY = StreamRepresentationPolicy.defaultPolicy();

    private final MediaProber mediaProber;

    public LocalPlaybackDecisionService(MediaProber mediaProber) {
        this.mediaProber = mediaProber;
    }

    @Override
    public PlaybackDecision decide(PlaybackRequest request) {
        ProbeResult probeResult = request.source().characteristics() != null
            ? toProbeResult(request.source().characteristics(), request.source().path())
            : probe(request.source().path());

        PlaybackCapabilitySet capabilities = request.capabilities() != null
            ? request.capabilities()
            : request.clientProfile() != null && request.clientProfile().capabilities() != null
                ? request.clientProfile().capabilities()
                : new PlaybackCapabilitySet();
        PlaybackConstraints constraints = resolveConstraints(request);
        VideoStream selectedVideo = probeResult.getStreams().getVideo().stream().findFirst().orElse(null);
        List<AudioStream> selectedAudio = resolveSelectedAudioStreams(request, probeResult);
        SubtitleStream selectedSubtitle = resolveSelectedSubtitleStream(request, probeResult);
        LinkedHashSet<PlaybackReason> reasons = new LinkedHashSet<>();
        String sourceContainer = normalizeContainer(probeResult.getFormat());
        boolean containerUnsupported = !isSupported(sourceContainer, capabilities.supportedContainers());
        if (containerUnsupported) {
            reasons.add(PlaybackReason.CONTAINER_UNSUPPORTED);
            reasons.add(PlaybackReason.CLIENT_CAPABILITY_LIMIT);
        }
        SubtitleDecision subtitleDecision = resolveSubtitleDecision(
            request,
            selectedSubtitle,
            selectedVideo,
            capabilities,
            reasons
        );
        boolean videoTranscodeRequired = evaluateVideoCompatibility(
            constraints,
            capabilities,
            selectedVideo,
            reasons
        );
        boolean audioTranscodeRequired = evaluateAudioCompatibility(
            constraints,
            capabilities,
            selectedAudio,
            reasons
        );
        CompatibilityState compatibilityState = new CompatibilityState(
            sourceContainer,
            selectedVideo,
            selectedAudio,
            subtitleDecision,
            containerUnsupported,
            videoTranscodeRequired,
            audioTranscodeRequired,
            new LinkedHashSet<>(reasons)
        );

        boolean packagingRequested = prefersPackagedOutput(request);
        StreamingProtocol transformedProtocol = chooseTransformedProtocol(
            request.output().allowedProtocols(),
            request.output().preferredProtocol()
        );
        StreamRepresentation transformedRepresentation = chooseTransformedRepresentation(request, transformedProtocol);
        boolean sourceCompatible = !compatibilityState.containerUnsupported()
            && !compatibilityState.videoTranscodeRequired()
            && !compatibilityState.audioTranscodeRequired()
            && !compatibilityState.subtitleDecision().burnInRequired();

        if (
            sourceCompatible
                && !packagingRequested
                && request.output().allowedProtocols().contains(StreamingProtocol.FILE)
        ) {
            if (capabilities.allowDirectPlay()) {
                return buildDirectPlayDecision(compatibilityState);
            }
            reasons.add(PlaybackReason.EXPLICIT_TRANSCODE_REQUEST);
        }

        if (compatibilityState.subtitleDecision().burnInRequired()) {
            ensureRepresentationAvailable(transformedRepresentation, "subtitle burn-in output");
            ensureAllowed(
                capabilities.allowSubtitleBurnIn() && capabilities.allowVideoTranscode(),
                "Subtitle burn-in is required but not allowed by playback capabilities"
            );
            applyVideoRepresentationConstraints(request, transformedRepresentation);
            return buildSubtitleBurnInDecision(request, compatibilityState, transformedRepresentation, reasons);
        }

        if (compatibilityState.videoTranscodeRequired()) {
            ensureRepresentationAvailable(transformedRepresentation, "video transcode output");
            ensureAllowed(
                capabilities.allowVideoTranscode(),
                "Video transcode is required but not allowed by playback capabilities"
            );
            applyVideoRepresentationConstraints(request, transformedRepresentation);
            return buildVideoTranscodeDecision(request, compatibilityState, transformedRepresentation, reasons);
        }

        if (compatibilityState.audioTranscodeRequired()) {
            ensureRepresentationAvailable(transformedRepresentation, "audio transcode output");
            ensureAllowed(
                capabilities.allowAudioTranscode(),
                "Audio transcode is required but not allowed by playback capabilities"
            );
            return buildAudioTranscodeDecision(request, compatibilityState, transformedRepresentation, reasons);
        }

        ensureRepresentationAvailable(transformedRepresentation, "packaged stream output");
        reasons.add(PlaybackReason.ADAPTIVE_STREAMING_REQUESTED);
        if (!capabilities.allowRemux()) {
            reasons.add(PlaybackReason.EXPLICIT_TRANSCODE_REQUEST);
            ensureAllowed(
                capabilities.allowVideoTranscode(),
                "Remux is required but not allowed by playback capabilities"
            );
            applyVideoRepresentationConstraints(request, transformedRepresentation);
            return buildVideoTranscodeDecision(request, compatibilityState, transformedRepresentation, reasons);
        }

        return buildRemuxDecision(request, compatibilityState, transformedRepresentation, reasons);
    }

    private ProbeResult probe(String path) {
        try {
            return probeCachedOrThrow(mediaProber, Path.of(path));
        } catch (Throwable throwable) {
            return sneakyThrow(new NyxException(
                ErrorCode.PROBE_FAILED,
                "Failed to probe playback source '" + path + "': " + messageOrUnknown(throwable),
                Map.of(),
                throwable
            ));
        }
    }

    private PlaybackDecision buildDirectPlayDecision(CompatibilityState compatibilityState) {
        return new PlaybackDecision(
            PlaybackMode.DIRECT_PLAY,
            new StreamDescriptor(StreamingProtocol.FILE, compatibilityState.sourceContainer(), false),
            compatibilityState.reasons(),
            compatibilityState.selectedVideo() != null,
            true,
            compatibilityState.subtitleDecision().delivery(),
            new PlaybackOutputSummary(
                compatibilityState.selectedVideo() != null
                    ? normalizeCodec(compatibilityState.selectedVideo().getCodec())
                    : null,
                normalizeAudioCodecs(compatibilityState.selectedAudio())
            )
        );
    }

    private PlaybackDecision buildRemuxDecision(
        PlaybackRequest request,
        CompatibilityState compatibilityState,
        StreamRepresentation representation,
        Set<PlaybackReason> reasons
    ) {
        return new PlaybackDecision(
            PlaybackMode.REMUX,
            streamDescriptor(request, representation),
            new LinkedHashSet<>(reasons),
            compatibilityState.selectedVideo() != null,
            true,
            compatibilityState.subtitleDecision().delivery(),
            new PlaybackOutputSummary(
                compatibilityState.selectedVideo() != null
                    ? normalizeCodec(compatibilityState.selectedVideo().getCodec())
                    : null,
                normalizeAudioCodecs(compatibilityState.selectedAudio())
            )
        );
    }

    private PlaybackDecision buildAudioTranscodeDecision(
        PlaybackRequest request,
        CompatibilityState compatibilityState,
        StreamRepresentation representation,
        Set<PlaybackReason> reasons
    ) {
        Profile profile = resolveTranscodeProfile(request);
        return new PlaybackDecision(
            PlaybackMode.AUDIO_TRANSCODE,
            streamDescriptor(request, representation),
            new LinkedHashSet<>(reasons),
            compatibilityState.selectedVideo() != null,
            false,
            compatibilityState.subtitleDecision().delivery(),
            new PlaybackOutputSummary(
                compatibilityState.selectedVideo() != null
                    ? normalizeCodec(compatibilityState.selectedVideo().getCodec())
                    : null,
                Set.of(audioCodecName(profile.getAudioCodec()))
            )
        );
    }

    private PlaybackDecision buildVideoTranscodeDecision(
        PlaybackRequest request,
        CompatibilityState compatibilityState,
        StreamRepresentation representation,
        Set<PlaybackReason> reasons
    ) {
        Profile profile = resolveTranscodeProfile(request);
        return new PlaybackDecision(
            PlaybackMode.VIDEO_TRANSCODE,
            streamDescriptor(request, representation),
            new LinkedHashSet<>(reasons),
            false,
            false,
            compatibilityState.subtitleDecision().delivery(),
            new PlaybackOutputSummary(
                videoCodecName(profile.getVideoCodec()),
                Set.of(audioCodecName(profile.getAudioCodec()))
            )
        );
    }

    private PlaybackDecision buildSubtitleBurnInDecision(
        PlaybackRequest request,
        CompatibilityState compatibilityState,
        StreamRepresentation representation,
        Set<PlaybackReason> reasons
    ) {
        Profile profile = resolveTranscodeProfile(request);
        return new PlaybackDecision(
            PlaybackMode.SUBTITLE_BURN_IN,
            streamDescriptor(request, representation),
            new LinkedHashSet<>(reasons),
            false,
            false,
            SubtitleDelivery.BURN_IN,
            new PlaybackOutputSummary(
                videoCodecName(profile.getVideoCodec()),
                Set.of(audioCodecName(profile.getAudioCodec()))
            )
        );
    }

    private boolean evaluateVideoCompatibility(
        PlaybackConstraints constraints,
        PlaybackCapabilitySet capabilities,
        VideoStream selectedVideo,
        Set<PlaybackReason> reasons
    ) {
        if (selectedVideo == null) {
            return false;
        }
        boolean requiresTranscode = false;
        if (!isSupported(normalizeCodec(selectedVideo.getCodec()), capabilities.supportedVideoCodecs())) {
            reasons.add(PlaybackReason.VIDEO_CODEC_UNSUPPORTED);
            reasons.add(PlaybackReason.CLIENT_CAPABILITY_LIMIT);
            requiresTranscode = true;
        }
        if (constraints.video().maxBitrateKbps() != null) {
            Integer sourceBitrate = selectedVideo.getBitrateKbps();
            if (sourceBitrate != null && sourceBitrate > constraints.video().maxBitrateKbps()) {
                reasons.add(PlaybackReason.VIDEO_BITRATE_TOO_HIGH);
                requiresTranscode = true;
            }
        }
        if (constraints.video().maxWidth() != null && selectedVideo.getWidth() > constraints.video().maxWidth()) {
            reasons.add(PlaybackReason.VIDEO_RESOLUTION_TOO_HIGH);
            requiresTranscode = true;
        }
        if (constraints.video().maxHeight() != null && selectedVideo.getHeight() > constraints.video().maxHeight()) {
            reasons.add(PlaybackReason.VIDEO_RESOLUTION_TOO_HIGH);
            requiresTranscode = true;
        }
        return requiresTranscode;
    }

    private boolean evaluateAudioCompatibility(
        PlaybackConstraints constraints,
        PlaybackCapabilitySet capabilities,
        List<AudioStream> selectedAudio,
        Set<PlaybackReason> reasons
    ) {
        boolean requiresTranscode = false;
        for (AudioStream audioStream : selectedAudio) {
            if (!isSupported(normalizeCodec(audioStream.getCodec()), capabilities.supportedAudioCodecs())) {
                reasons.add(PlaybackReason.AUDIO_CODEC_UNSUPPORTED);
                reasons.add(PlaybackReason.CLIENT_CAPABILITY_LIMIT);
                requiresTranscode = true;
            }
            if (constraints.audio().maxBitrateKbps() != null) {
                Integer sourceBitrate = audioStream.getBitrateKbps();
                if (sourceBitrate != null && sourceBitrate > constraints.audio().maxBitrateKbps()) {
                    reasons.add(PlaybackReason.AUDIO_BITRATE_TOO_HIGH);
                    requiresTranscode = true;
                }
            }
            if (constraints.audio().maxChannels() != null && audioStream.getChannels() > constraints.audio().maxChannels()) {
                reasons.add(PlaybackReason.AUDIO_CHANNELS_TOO_HIGH);
                requiresTranscode = true;
            }
        }
        return requiresTranscode;
    }

    private SubtitleDecision resolveSubtitleDecision(
        PlaybackRequest request,
        SubtitleStream selectedSubtitle,
        VideoStream selectedVideo,
        PlaybackCapabilitySet capabilities,
        Set<PlaybackReason> reasons
    ) {
        return switch (request.selection().subtitles().mode()) {
            case DISABLE -> new SubtitleDecision(SubtitleDelivery.NONE, false);
            case BURN_IN -> {
                if (selectedSubtitle == null) {
                    sneakyThrow(nyxException(
                        ErrorCode.INVALID_REQUEST,
                        "Subtitle burn-in requested but no subtitle track was selected"
                    ));
                }
                if (selectedVideo == null) {
                    sneakyThrow(nyxException(ErrorCode.INVALID_REQUEST, "Subtitle burn-in requires a video stream"));
                }
                reasons.add(PlaybackReason.SUBTITLE_BURN_IN_REQUIRED);
                yield new SubtitleDecision(SubtitleDelivery.BURN_IN, true);
            }
            case EXTRACT -> {
                if (selectedSubtitle == null) {
                    yield new SubtitleDecision(SubtitleDelivery.NONE, false);
                }
                boolean subtitleSupported = capabilities.supportedSubtitleFormats().isEmpty()
                    || capabilities.supportedSubtitleFormats().stream()
                        .anyMatch(format -> normalizeCodec(format).equals(normalizeCodec(selectedSubtitle.getCodec())));
                if (subtitleSupported) {
                    yield new SubtitleDecision(SubtitleDelivery.SIDECAR, false);
                }
                if (selectedVideo == null) {
                    sneakyThrow(nyxException(
                        ErrorCode.INVALID_REQUEST,
                        "Unsupported subtitle extraction requires a video stream for burn-in"
                    ));
                }
                reasons.add(PlaybackReason.SUBTITLE_BURN_IN_REQUIRED);
                reasons.add(PlaybackReason.CLIENT_CAPABILITY_LIMIT);
                yield new SubtitleDecision(SubtitleDelivery.BURN_IN, true);
            }
        };
    }

    private PlaybackConstraints resolveConstraints(PlaybackRequest request) {
        PlaybackConstraints profileConstraints = request.clientProfile() != null && request.clientProfile().constraints() != null
            ? request.clientProfile().constraints()
            : new PlaybackConstraints();
        return new PlaybackConstraints(
            new VideoConstraint(
                request.constraints().video().maxWidth() != null
                    ? request.constraints().video().maxWidth()
                    : profileConstraints.video().maxWidth(),
                request.constraints().video().maxHeight() != null
                    ? request.constraints().video().maxHeight()
                    : profileConstraints.video().maxHeight(),
                request.constraints().video().maxBitrateKbps() != null
                    ? request.constraints().video().maxBitrateKbps()
                    : profileConstraints.video().maxBitrateKbps()
            ),
            new AudioConstraint(
                request.constraints().audio().maxBitrateKbps() != null
                    ? request.constraints().audio().maxBitrateKbps()
                    : profileConstraints.audio().maxBitrateKbps(),
                request.constraints().audio().maxChannels() != null
                    ? request.constraints().audio().maxChannels()
                    : profileConstraints.audio().maxChannels(),
                request.constraints().audio().preferredBitrateKbps() != null
                    ? request.constraints().audio().preferredBitrateKbps()
                    : profileConstraints.audio().preferredBitrateKbps(),
                request.constraints().audio().maxSampleRateHz() != null
                    ? request.constraints().audio().maxSampleRateHz()
                    : profileConstraints.audio().maxSampleRateHz(),
                request.constraints().audio().preferredSampleRateHz() != null
                    ? request.constraints().audio().preferredSampleRateHz()
                    : profileConstraints.audio().preferredSampleRateHz()
            )
        );
    }

    private List<AudioStream> resolveSelectedAudioStreams(PlaybackRequest request, ProbeResult probeResult) {
        List<AudioStream> audioStreams = probeResult.getStreams().getAudio();
        return switch (request.selection().audio().mode()) {
            case ALL -> audioStreams;
            case DEFAULT -> audioStreams.isEmpty() ? List.of() : List.of(audioStreams.getFirst());
            case SPECIFIC -> {
                List<AudioStream> selectedByIndex = new ArrayList<>();
                for (Integer requestedIndex : request.selection().audio().trackIndices()) {
                    AudioStream stream = audioStreams.stream()
                        .filter(audioStream -> audioStream.getIndex() == requestedIndex)
                        .findFirst()
                        .orElse(null);
                    if (stream == null) {
                        sneakyThrow(nyxException(
                            ErrorCode.INVALID_REQUEST,
                            "Requested audio track " + requestedIndex + " was not found in source media"
                        ));
                    }
                    selectedByIndex.add(stream);
                }
                if (selectedByIndex.isEmpty()) {
                    sneakyThrow(nyxException(
                        ErrorCode.INVALID_REQUEST,
                        "Specific audio track selection requires at least one track index"
                    ));
                }
                yield List.copyOf(selectedByIndex);
            }
        };
    }

    private SubtitleStream resolveSelectedSubtitleStream(PlaybackRequest request, ProbeResult probeResult) {
        List<SubtitleStream> subtitleStreams = probeResult.getStreams().getSubtitle();
        Integer requestedTrackIndex = request.selection().subtitles().trackIndex();
        return switch (request.selection().subtitles().mode()) {
            case DISABLE -> null;
            case EXTRACT -> {
                if (requestedTrackIndex == null) {
                    yield null;
                }
                SubtitleStream stream = subtitleStreams.stream()
                    .filter(subtitleStream -> subtitleStream.getIndex() == requestedTrackIndex)
                    .findFirst()
                    .orElse(null);
                if (stream == null) {
                    sneakyThrow(nyxException(
                        ErrorCode.INVALID_REQUEST,
                        "Requested subtitle track " + requestedTrackIndex + " was not found in source media"
                    ));
                }
                yield stream;
            }
            case BURN_IN -> {
                if (requestedTrackIndex != null) {
                    SubtitleStream stream = subtitleStreams.stream()
                        .filter(subtitleStream -> subtitleStream.getIndex() == requestedTrackIndex)
                        .findFirst()
                        .orElse(null);
                    if (stream == null) {
                        sneakyThrow(nyxException(
                            ErrorCode.INVALID_REQUEST,
                            "Requested subtitle track " + requestedTrackIndex + " was not found in source media"
                        ));
                    }
                    yield stream;
                }
                SubtitleStream firstStream = subtitleStreams.stream().findFirst().orElse(null);
                if (firstStream == null) {
                    sneakyThrow(nyxException(
                        ErrorCode.INVALID_REQUEST,
                        "Subtitle burn-in requested but no subtitle streams exist in source media"
                    ));
                }
                yield firstStream;
            }
        };
    }

    private boolean prefersPackagedOutput(PlaybackRequest request) {
        StreamingProtocol preferred = request.output().preferredProtocol();
        return (preferred != null && preferred != StreamingProtocol.FILE)
            || !request.output().allowedProtocols().contains(StreamingProtocol.FILE);
    }

    private StreamingProtocol chooseTransformedProtocol(
        Set<StreamingProtocol> allowedProtocols,
        StreamingProtocol preferredProtocol
    ) {
        if (
            preferredProtocol != null
                && preferredProtocol != StreamingProtocol.FILE
                && allowedProtocols.contains(preferredProtocol)
        ) {
            return preferredProtocol;
        }
        if (allowedProtocols.contains(StreamingProtocol.HLS)) {
            return StreamingProtocol.HLS;
        }
        if (allowedProtocols.contains(StreamingProtocol.DASH)) {
            return StreamingProtocol.DASH;
        }
        return null;
    }

    private StreamRepresentation chooseTransformedRepresentation(
        PlaybackRequest request,
        StreamingProtocol transformedProtocol
    ) {
        StreamRepresentation preferredRepresentation = request.output().preferredRepresentation();
        if (preferredRepresentation != null) {
            if (preferredRepresentation == StreamRepresentation.DIRECT_FILE) {
                return null;
            }
            if (!request.output().allowedProtocols().containsAll(REPRESENTATION_POLICY.traits(preferredRepresentation).protocols())) {
                return null;
            }
            return preferredRepresentation;
        }
        return REPRESENTATION_POLICY.defaultFor(transformedProtocol, null, request.output().allowAdaptiveStreaming());
    }

    private void applyVideoRepresentationConstraints(
        PlaybackRequest request,
        StreamRepresentation representation
    ) {
        int representationCount = effectiveVideoRepresentationCount(request);
        for (StreamRepresentationConstraint constraint : REPRESENTATION_POLICY.constraints(representation)) {
            if (constraint.kind() == StreamRepresentationConstraintKind.MAX_VIDEO_REPRESENTATIONS
                && representationCount > constraint.maxRepresentations()) {
                sneakyThrow(nyxException(ErrorCode.INVALID_REQUEST, constraint.violationMessage()));
            }
        }
    }

    private int effectiveVideoRepresentationCount(PlaybackRequest request) {
        if (!request.transcode().explicitRepresentations().isEmpty()) {
            return request.transcode().explicitRepresentations().size();
        }
        Profile profile = resolveTranscodeProfile(request);
        if (profile instanceof AdaptiveProfile adaptiveProfile) {
            return adaptiveProfile.getRepresentations().size();
        }
        return 0;
    }

    private Profile resolveTranscodeProfile(PlaybackRequest request) {
        String profileName = request.transcode().profileHint() != null
            ? request.transcode().profileHint()
            : DEFAULT_TRANSCODE_PROFILE;
        Profile profile = TranscodeProfiles.findByName(profileName);
        if (profile == null) {
            return sneakyThrow(nyxException(ErrorCode.INVALID_REQUEST, "Unknown profile: " + profileName));
        }
        return profile;
    }

    private boolean isSupported(String value, Set<String> supportedValues) {
        if (supportedValues.isEmpty()) {
            return true;
        }
        String normalizedValue = normalizeCodec(value);
        return supportedValues.stream().anyMatch(supported -> normalizeCodec(supported).equals(normalizedValue));
    }

    private String normalizeContainer(String format) {
        String primaryFormat = format.substring(0, format.indexOf(',') >= 0 ? format.indexOf(',') : format.length())
            .trim()
            .toLowerCase();
        return switch (primaryFormat) {
            case "matroska" -> "mkv";
            case "mov" -> "mp4";
            default -> primaryFormat;
        };
    }

    private String normalizeCodec(String codec) {
        String normalized = codec.trim().toLowerCase();
        return switch (normalized) {
            case "hevc" -> "h265";
            case "subrip" -> "srt";
            case "hdmv_pgs_subtitle" -> "pgs";
            default -> normalized;
        };
    }

    private String videoCodecName(VideoCodec videoCodec) {
        if (videoCodec instanceof VideoCodec.Copy) {
            return "copy";
        }
        if (videoCodec instanceof VideoCodec.H264) {
            return "h264";
        }
        if (videoCodec instanceof VideoCodec.H265) {
            return "h265";
        }
        if (videoCodec instanceof VideoCodec.AV1Svt) {
            return "av1";
        }
        throw new IllegalStateException("Unsupported video codec: " + videoCodec);
    }

    private String audioCodecName(AudioCodec audioCodec) {
        if (audioCodec instanceof AudioCodec.Copy) {
            return "copy";
        }
        if (audioCodec instanceof AudioCodec.AAC) {
            return "aac";
        }
        if (audioCodec instanceof AudioCodec.Opus) {
            return "opus";
        }
        throw new IllegalStateException("Unsupported audio codec: " + audioCodec);
    }

    private StreamDescriptor streamDescriptor(PlaybackRequest request, StreamRepresentation representation) {
        StreamRepresentationTraits traits = REPRESENTATION_POLICY.traits(representation);
        return new StreamDescriptor(
            traits.primaryProtocol(),
            traits.segmentContainer().token(),
            request.output().allowAdaptiveStreaming(),
            representation
        );
    }

    private void ensureRepresentationAvailable(StreamRepresentation representation, String target) {
        if (representation == null) {
            sneakyThrow(nyxException(
                ErrorCode.INVALID_REQUEST,
                "Playback request cannot produce " + target + " with the allowed stream representations"
            ));
        }
    }

    private void ensureAllowed(boolean condition, String message) {
        if (!condition) {
            sneakyThrow(nyxException(ErrorCode.INVALID_REQUEST, message));
        }
    }

    private ProbeResult toProbeResult(PlaybackSourceCharacteristics characteristics, String path) {
        List<VideoStream> videoStreams = characteristics.videoStreams().stream()
            .map(this::toVideoStream)
            .toList();
        List<AudioStream> audioStreams = characteristics.audioStreams().stream()
            .map(this::toAudioStream)
            .toList();
        List<SubtitleStream> subtitleStreams = characteristics.subtitleStreams().stream()
            .map(this::toSubtitleStream)
            .toList();
        return new ProbeResult(
            path,
            characteristics.container(),
            (characteristics.durationMillis() != null ? characteristics.durationMillis() : 0L) / 1000.0,
            characteristics.sizeBytes() != null ? characteristics.sizeBytes() : 0L,
            new ProbeStreams(videoStreams, audioStreams, subtitleStreams),
            Map.of()
        );
    }

    private VideoStream toVideoStream(PlaybackSourceVideoStream stream) {
        return new VideoStream(
            stream.index(),
            stream.codec(),
            stream.width(),
            stream.height(),
            stream.fps(),
            stream.bitrateKbps()
        );
    }

    private AudioStream toAudioStream(PlaybackSourceAudioStream stream) {
        return new AudioStream(
            stream.index(),
            stream.codec(),
            stream.channels(),
            stream.bitrateKbps(),
            stream.sampleRateHz(),
            stream.language(),
            stream.title()
        );
    }

    private SubtitleStream toSubtitleStream(PlaybackSourceSubtitleStream stream) {
        return new SubtitleStream(stream.index(), stream.codec(), stream.language(), stream.title());
    }

    private Set<String> normalizeAudioCodecs(List<AudioStream> streams) {
        LinkedHashSet<String> codecs = new LinkedHashSet<>();
        for (AudioStream stream : streams) {
            codecs.add(normalizeCodec(stream.getCodec()));
        }
        return codecs;
    }

    private String messageOrUnknown(Throwable throwable) {
        return throwable.getMessage() != null ? throwable.getMessage() : "unknown error";
    }

    private NyxException nyxException(ErrorCode errorCode, String message) {
        return new NyxException(errorCode, message, Map.of(), null);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private record SubtitleDecision(
        SubtitleDelivery delivery,
        boolean burnInRequired
    ) {}

    private record CompatibilityState(
        String sourceContainer,
        VideoStream selectedVideo,
        List<AudioStream> selectedAudio,
        SubtitleDecision subtitleDecision,
        boolean containerUnsupported,
        boolean videoTranscodeRequired,
        boolean audioTranscodeRequired,
        Set<PlaybackReason> reasons
    ) {}
}

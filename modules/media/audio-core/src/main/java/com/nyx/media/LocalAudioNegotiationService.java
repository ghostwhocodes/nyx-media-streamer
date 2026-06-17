package com.nyx.media;

import com.nyx.common.ErrorCode;
import com.nyx.common.MediaTypes;
import com.nyx.common.NyxException;
import com.nyx.playback.contracts.AudioCapabilitySet;
import com.nyx.playback.contracts.AudioConstraint;
import com.nyx.playback.contracts.AudioDeliveryMode;
import com.nyx.playback.contracts.AudioFormatDescriptor;
import com.nyx.playback.contracts.AudioNegotiationDecision;
import com.nyx.playback.contracts.AudioNegotiationRequest;
import com.nyx.playback.contracts.AudioNegotiationService;
import com.nyx.playback.contracts.PlaybackReason;
import com.nyx.playback.contracts.PlaybackSourceAudioStream;
import com.nyx.playback.contracts.PlaybackSourceCharacteristics;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LocalAudioNegotiationService implements AudioNegotiationService {
    private final AudioTranscoder audioTranscoder;

    public LocalAudioNegotiationService(AudioTranscoder audioTranscoder) {
        this.audioTranscoder = audioTranscoder;
    }

    @Override
    public AudioNegotiationDecision decide(AudioNegotiationRequest request) {
        PlaybackSourceCharacteristics characteristics = request.source().characteristics();
        if (characteristics == null) {
            sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "Audio negotiation requires source characteristics for " + request.source().path(),
                Map.of(),
                null
            ));
        }
        PlaybackSourceAudioStream sourceAudio = characteristics.audioStreams().stream().findFirst().orElse(null);
        if (sourceAudio == null) {
            sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "Audio negotiation requires at least one audio stream for " + request.source().path(),
                Map.of(),
                null
            ));
        }

        AudioFormatDescriptor source = buildSourceDescriptor(request.source().path(), characteristics, sourceAudio);
        LinkedHashSet<PlaybackReason> reasons = new LinkedHashSet<>();

        if (canDirectPlay(request, source, reasons)) {
            return new AudioNegotiationDecision(AudioDeliveryMode.DIRECT_PLAY, Set.of(), source, source);
        }

        if (!request.capabilities().allowTranscode()) {
            sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "Audio request requires transcoding but audio capabilities disallow it",
                Map.of(),
                null
            ));
        }

        AudioTranscoder.TranscodeTarget target = selectTranscodeTarget(request);
        if (target == null) {
            sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "Audio request cannot produce a compatible transcode target",
                Map.of(),
                null
            ));
        }

        return new AudioNegotiationDecision(
            AudioDeliveryMode.TRANSCODE,
            reasons,
            source,
            toOutputDescriptor(withResolvedConstraints(target, request.constraints()), source, request.constraints())
        );
    }

    private boolean canDirectPlay(
        AudioNegotiationRequest request,
        AudioFormatDescriptor source,
        Set<PlaybackReason> reasons
    ) {
        AudioCapabilitySet capabilities = request.capabilities();
        if (!capabilities.allowDirectPlay()) {
            reasons.add(PlaybackReason.EXPLICIT_TRANSCODE_REQUEST);
            return false;
        }
        if (request.startPositionMillis() > 0) {
            reasons.add(PlaybackReason.EXPLICIT_TRANSCODE_REQUEST);
            return false;
        }

        boolean compatible = true;
        if (!isMimeSupported(source.mimeType(), capabilities) || !isValueSupported(source.container(), capabilities.supportedContainers())) {
            reasons.add(PlaybackReason.CONTAINER_UNSUPPORTED);
            reasons.add(PlaybackReason.CLIENT_CAPABILITY_LIMIT);
            compatible = false;
        }
        if (!isValueSupported(source.codec(), capabilities.supportedAudioCodecs())) {
            reasons.add(PlaybackReason.AUDIO_CODEC_UNSUPPORTED);
            reasons.add(PlaybackReason.CLIENT_CAPABILITY_LIMIT);
            compatible = false;
        }

        AudioConstraint constraints = request.constraints();
        if (exceedsBitrate(source, constraints)) {
            reasons.add(PlaybackReason.AUDIO_BITRATE_TOO_HIGH);
            compatible = false;
        }
        if (exceedsChannels(source, constraints)) {
            reasons.add(PlaybackReason.AUDIO_CHANNELS_TOO_HIGH);
            compatible = false;
        }
        if (exceedsSampleRate(source, constraints)) {
            reasons.add(PlaybackReason.AUDIO_SAMPLE_RATE_TOO_HIGH);
            compatible = false;
        }
        return compatible;
    }

    private AudioTranscoder.TranscodeTarget selectTranscodeTarget(AudioNegotiationRequest request) {
        List<String> preferredMimeTypes = request.output().preferredMimeTypes().stream().map(LocalAudioNegotiationService::normalizeValue).distinct().toList();
        List<String> preferredContainers = request.output().preferredContainers().stream().map(LocalAudioNegotiationService::normalizeValue).distinct().toList();
        List<String> preferredCodecs = request.output().preferredAudioCodecs().stream().map(LocalAudioNegotiationService::normalizeValue).distinct().toList();
        AudioCapabilitySet capabilities = request.capabilities();

        return audioTranscoder.availableTargets().stream()
            .filter(target ->
                isMimeSupported(target.mimeType(), capabilities) &&
                    isValueSupported(target.format(), capabilities.supportedContainers()) &&
                    isValueSupported(target.outputCodec(), capabilities.supportedAudioCodecs()) &&
                    matchesRequestedOutputs(target, preferredMimeTypes, preferredContainers, preferredCodecs))
            .sorted((left, right) -> {
                int byMime = Integer.compare(preferenceIndex(preferredMimeTypes, left.mimeType()), preferenceIndex(preferredMimeTypes, right.mimeType()));
                if (byMime != 0) {
                    return byMime;
                }
                int byContainer = Integer.compare(preferenceIndex(preferredContainers, left.format()), preferenceIndex(preferredContainers, right.format()));
                if (byContainer != 0) {
                    return byContainer;
                }
                return Integer.compare(preferenceIndex(preferredCodecs, left.outputCodec()), preferenceIndex(preferredCodecs, right.outputCodec()));
            })
            .findFirst()
            .map(target -> withResolvedConstraints(target, request.constraints()))
            .orElse(null);
    }

    private boolean matchesRequestedOutputs(
        AudioTranscoder.TranscodeTarget target,
        List<String> preferredMimeTypes,
        List<String> preferredContainers,
        List<String> preferredCodecs
    ) {
        if (!preferredMimeTypes.isEmpty() && preferenceIndex(preferredMimeTypes, target.mimeType()) == Integer.MAX_VALUE) {
            return false;
        }
        if (!preferredContainers.isEmpty() && preferenceIndex(preferredContainers, target.format()) == Integer.MAX_VALUE) {
            return false;
        }
        if (!preferredCodecs.isEmpty() && preferenceIndex(preferredCodecs, target.outputCodec()) == Integer.MAX_VALUE) {
            return false;
        }
        return true;
    }

    private AudioTranscoder.TranscodeTarget withResolvedConstraints(AudioTranscoder.TranscodeTarget target, AudioConstraint constraints) {
        int resolvedBitrate = resolveBitrateKbps(target.bitrate(), constraints);
        Integer resolvedSampleRate = resolveSampleRateHz(constraints);
        return new AudioTranscoder.TranscodeTarget(
            target.mimeType(),
            target.format(),
            target.codec(),
            resolvedBitrate + "k",
            target.outputCodec(),
            constraints.maxChannels(),
            resolvedSampleRate
        );
    }

    private AudioFormatDescriptor toOutputDescriptor(
        AudioTranscoder.TranscodeTarget target,
        AudioFormatDescriptor source,
        AudioConstraint constraints
    ) {
        Integer resolvedChannels;
        if (target.channels() != null && source.channels() != null) {
            resolvedChannels = Math.min(source.channels(), target.channels());
        } else if (target.channels() != null) {
            resolvedChannels = target.channels();
        } else {
            resolvedChannels = source.channels();
        }
        return new AudioFormatDescriptor(
            target.format(),
            normalizeValue(target.outputCodec()),
            target.mimeType(),
            parseBitrate(target.bitrate()),
            resolvedChannels,
            target.sampleRateHz() != null ? target.sampleRateHz() : source.sampleRateHz() != null ? source.sampleRateHz() : constraints.maxSampleRateHz() != null ? constraints.maxSampleRateHz() : constraints.preferredSampleRateHz()
        );
    }

    private AudioFormatDescriptor buildSourceDescriptor(
        String path,
        PlaybackSourceCharacteristics characteristics,
        PlaybackSourceAudioStream audioStream
    ) {
        String container = normalizeContainer(characteristics.container(), path);
        String mimeType = MediaTypes.INSTANCE.mimeTypeForPath(path);
        if (mimeType == null) {
            mimeType = mimeTypeForContainer(container);
        }
        return new AudioFormatDescriptor(
            container,
            normalizeValue(audioStream.codec()),
            mimeType,
            audioStream.bitrateKbps(),
            audioStream.channels(),
            audioStream.sampleRateHz()
        );
    }

    private boolean isMimeSupported(String mimeType, AudioCapabilitySet capabilities) {
        if (mimeType == null || capabilities.supportedMimeTypes().isEmpty()) {
            return true;
        }
        String normalizedMimeType = normalizeValue(mimeType);
        return capabilities.supportedMimeTypes().stream().map(LocalAudioNegotiationService::normalizeValue).anyMatch(normalizedMimeType::equals);
    }

    private boolean isValueSupported(String value, Set<String> supportedValues) {
        if (value == null || supportedValues.isEmpty()) {
            return true;
        }
        String normalizedValue = normalizeValue(value);
        return supportedValues.stream().map(LocalAudioNegotiationService::normalizeValue).anyMatch(normalizedValue::equals);
    }

    private boolean exceedsBitrate(AudioFormatDescriptor source, AudioConstraint constraints) {
        return source.bitrateKbps() != null && constraints.maxBitrateKbps() != null && source.bitrateKbps() > constraints.maxBitrateKbps();
    }

    private boolean exceedsChannels(AudioFormatDescriptor source, AudioConstraint constraints) {
        return source.channels() != null && constraints.maxChannels() != null && source.channels() > constraints.maxChannels();
    }

    private boolean exceedsSampleRate(AudioFormatDescriptor source, AudioConstraint constraints) {
        return source.sampleRateHz() != null && constraints.maxSampleRateHz() != null && source.sampleRateHz() > constraints.maxSampleRateHz();
    }

    private int resolveBitrateKbps(String defaultBitrate, AudioConstraint constraints) {
        int baseline = parseBitrate(defaultBitrate) == null ? 192 : parseBitrate(defaultBitrate);
        Integer preferred = constraints.preferredBitrateKbps();
        Integer ceiling = constraints.maxBitrateKbps();
        if (preferred != null && ceiling != null) {
            return Math.min(preferred, ceiling);
        }
        if (preferred != null) {
            return preferred;
        }
        if (ceiling != null) {
            return Math.min(baseline, ceiling);
        }
        return baseline;
    }

    private Integer resolveSampleRateHz(AudioConstraint constraints) {
        Integer preferred = constraints.preferredSampleRateHz();
        Integer ceiling = constraints.maxSampleRateHz();
        if (preferred != null && ceiling != null) {
            return Math.min(preferred, ceiling);
        }
        if (preferred != null) {
            return preferred;
        }
        return ceiling;
    }

    private int preferenceIndex(List<String> preferences, String value) {
        if (preferences.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        return preferences.indexOf(normalizeValue(value));
    }

    private String normalizeContainer(String container, String path) {
        int lastDot = path.lastIndexOf('.');
        String extension = lastDot >= 0 ? path.substring(lastDot + 1).trim().toLowerCase() : "";
        if (!extension.isEmpty()) {
            return extension;
        }
        return container.split(",", 2)[0].trim().toLowerCase();
    }

    private String mimeTypeForContainer(String container) {
        return switch (normalizeValue(container)) {
            case "aac", "adts" -> MediaTypes.AUDIO_AAC;
            case "aif", "aiff" -> MediaTypes.AUDIO_AIFF;
            case "flac" -> MediaTypes.AUDIO_FLAC;
            case "m4a", "mp4" -> MediaTypes.AUDIO_M4A;
            case "mp3" -> MediaTypes.AUDIO_MP3;
            case "ogg" -> MediaTypes.AUDIO_OGG;
            case "opus" -> MediaTypes.AUDIO_OPUS;
            case "wav" -> MediaTypes.AUDIO_WAV;
            default -> null;
        };
    }

    private Integer parseBitrate(String bitrate) {
        try {
            return Integer.parseInt(bitrate.replace("k", ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String normalizeValue(String value) {
        return value.trim().toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }
}

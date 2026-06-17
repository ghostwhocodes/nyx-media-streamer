package com.nyx.media;

import com.nyx.playback.contracts.AudioCapabilitySet;
import com.nyx.playback.contracts.AudioClientIdentity;
import com.nyx.playback.contracts.AudioConstraint;
import com.nyx.playback.contracts.AudioNegotiationRequest;
import com.nyx.playback.contracts.AudioOutputPreferences;
import com.nyx.playback.contracts.MediaSourceRef;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class LegacyAudioNegotiationRequestFactory {
    private static final Set<String> DEFAULT_NEGOTIATED_MIME_TYPES = Set.of(
        "audio/aac",
        "audio/mpeg",
        "audio/opus"
    );

    private LegacyAudioNegotiationRequestFactory() {
    }

    public static AudioNegotiationRequest fromFileRequest(String path, String acceptHeader) {
        return fromFileRequest(path, acceptHeader, 0L, null, null, DEFAULT_NEGOTIATED_MIME_TYPES);
    }

    public static AudioNegotiationRequest fromFileRequest(
        String path,
        String acceptHeader,
        long startPositionMillis,
        AudioClientIdentity client,
        String sourceMimeType,
        Set<String> negotiatedMimeTypes
    ) {
        List<String> acceptedMimeTypes = parseAcceptHeader(acceptHeader);
        String normalizedSourceMimeType = sourceMimeType == null ? null : sourceMimeType.trim().toLowerCase();
        if (acceptedMimeTypes.isEmpty()
            || acceptedMimeTypes.stream().anyMatch(LegacyAudioNegotiationRequestFactory::isWildcardType)
            || normalizedSourceMimeType != null && acceptedMimeTypes.contains(normalizedSourceMimeType)) {
            return defaultRequest(path, startPositionMillis, client);
        }

        Set<String> normalizedNegotiatedMimeTypes = new LinkedHashSet<>();
        for (String mimeType : negotiatedMimeTypes) {
            normalizedNegotiatedMimeTypes.add(mimeType.trim().toLowerCase());
        }

        List<String> compatibleMimeTypes = acceptedMimeTypes.stream()
            .filter(normalizedNegotiatedMimeTypes::contains)
            .toList();
        if (compatibleMimeTypes.isEmpty()) {
            return defaultRequest(path, startPositionMillis, client);
        }

        List<AudioMimeDescriptor> descriptors = compatibleMimeTypes.stream()
            .map(LegacyAudioNegotiationRequestFactory::descriptorForMimeType)
            .toList();
        return new AudioNegotiationRequest(
            new MediaSourceRef(path),
            startPositionMillis,
            client,
            new AudioCapabilitySet(
                Set.copyOf(compatibleMimeTypes),
                descriptors.stream().map(AudioMimeDescriptor::container).filter(value -> value != null).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                descriptors.stream().map(AudioMimeDescriptor::codec).filter(value -> value != null).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                true,
                true
            ),
            new AudioConstraint(),
            new AudioOutputPreferences(
                compatibleMimeTypes,
                descriptors.stream().map(AudioMimeDescriptor::container).filter(value -> value != null).toList(),
                descriptors.stream().map(AudioMimeDescriptor::codec).filter(value -> value != null).toList()
            )
        );
    }

    private static AudioNegotiationRequest defaultRequest(String path, long startPositionMillis, AudioClientIdentity client) {
        return new AudioNegotiationRequest(
            new MediaSourceRef(path),
            startPositionMillis,
            client,
            new AudioCapabilitySet(),
            new AudioConstraint(),
            new AudioOutputPreferences()
        );
    }

    private static List<String> parseAcceptHeader(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        return List.of(header.split(",")).stream()
            .map(value -> value.trim().split(";", 2)[0].trim().toLowerCase())
            .filter(value -> !value.isEmpty())
            .distinct()
            .toList();
    }

    private static boolean isWildcardType(String mimeType) {
        return "*/*".equals(mimeType) || mimeType.endsWith("/*");
    }

    private static AudioMimeDescriptor descriptorForMimeType(String mimeType) {
        return switch (mimeType) {
            case "audio/aac" -> new AudioMimeDescriptor("adts", "aac");
            case "audio/flac" -> new AudioMimeDescriptor("flac", "flac");
            case "audio/mpeg" -> new AudioMimeDescriptor("mp3", "mp3");
            case "audio/mp4" -> new AudioMimeDescriptor("mp4", null);
            case "audio/ogg" -> new AudioMimeDescriptor("ogg", null);
            case "audio/opus" -> new AudioMimeDescriptor("opus", "opus");
            case "audio/wav" -> new AudioMimeDescriptor("wav", null);
            case "audio/aiff" -> new AudioMimeDescriptor("aiff", null);
            default -> new AudioMimeDescriptor(null, null);
        };
    }

    private record AudioMimeDescriptor(String container, String codec) {
    }
}

package com.nyx.transcode;

import static com.nyx.common.RouteUtilsJava.enforceUserRateLimit;
import static com.nyx.common.RouteUtilsJava.getLimitParam;
import static com.nyx.common.RouteUtilsJava.getPageParam;
import static com.nyx.common.RouteUtilsJava.resolvePathParam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.common.ErrorCode;
import com.nyx.common.ErrorDetail;
import com.nyx.common.ErrorResponse;
import com.nyx.common.HealthMonitor;
import com.nyx.common.MediaTypes;
import com.nyx.common.NyxException;
import com.nyx.common.PathSecurity;
import com.nyx.common.QuotaService;
import com.nyx.common.VirtualPathResolver;
import com.nyx.ffmpeg.MediaProber;
import com.nyx.ffmpeg.MediaProberInterop;
import com.nyx.ffmpeg.SubtitleExtractor;
import com.nyx.http.AuthMode;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.OpenApiRouteConfig;
import com.nyx.http.ParameterDoc;
import com.nyx.http.Route;
import com.nyx.http.RouteHandlerScope;
import com.nyx.http.RoutingCall;
import com.nyx.http.ServerSentEvent;
import com.nyx.http.SseRouteHandlerScope;
import com.nyx.http.UserIdPrincipal;
import com.nyx.json.NyxJson;
import com.nyx.playback.contracts.PlaybackDecision;
import com.nyx.playback.contracts.PlaybackDecisionService;
import com.nyx.playback.contracts.PlaybackRequest;
import com.nyx.playback.contracts.PlaybackSession;
import com.nyx.playback.contracts.PlaybackSessionService;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.transcode.contracts.BatchCancelRequest;
import com.nyx.transcode.contracts.BatchCancelResponse;
import com.nyx.transcode.contracts.BatchJobError;
import com.nyx.transcode.contracts.BatchStatusResponse;
import com.nyx.transcode.contracts.BatchSubmitResponse;
import com.nyx.transcode.contracts.BatchTranscodeRequest;
import com.nyx.transcode.contracts.JobEvent;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.PlaybackRequestMapper;
import com.nyx.transcode.contracts.SegmentCacheService;
import com.nyx.transcode.contracts.TranscodeApplicationService;
import com.nyx.transcode.contracts.TranscodeContracts;
import com.nyx.transcode.contracts.TranscodeExecutionMode;
import com.nyx.transcode.contracts.TranscodeJob;
import com.nyx.transcode.contracts.TranscodeRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

public final class TranscodeRoutes {
    private static final HttpStatusCode HTTP_OK = HttpStatusCode.Companion.getOK();
    private static final HttpStatusCode HTTP_CREATED = HttpStatusCode.Companion.getCreated();
    private static final HttpStatusCode HTTP_ACCEPTED = HttpStatusCode.Companion.getAccepted();
    private static final HttpStatusCode HTTP_NO_CONTENT = HttpStatusCode.Companion.getNoContent();
    private static final HttpStatusCode HTTP_BAD_REQUEST = HttpStatusCode.Companion.getBadRequest();
    private static final HttpStatusCode HTTP_NOT_FOUND = HttpStatusCode.Companion.getNotFound();
    private static final HttpStatusCode HTTP_SERVICE_UNAVAILABLE = HttpStatusCode.Companion.getServiceUnavailable();

    private TranscodeRoutes() {
    }

    public static void transcodeRoutes(
        Route route,
        TranscodeApplicationService transcodeService,
        SegmentCacheService segmentCache,
        MediaProber probeService,
        PathSecurity pathSecurity
    ) {
        transcodeRoutes(
            route,
            transcodeService,
            segmentCache,
            probeService,
            pathSecurity,
            List.of(),
            null,
            null,
            null,
            null,
            null
        );
    }

    @SuppressWarnings("LongMethod")
    public static void transcodeRoutes(
        Route route,
        TranscodeApplicationService transcodeService,
        SegmentCacheService segmentCache,
        MediaProber probeService,
        PathSecurity pathSecurity,
        List<String> authProviders,
        SubtitleExtractor subtitleExtractor,
        VirtualPathResolver virtualPathResolver,
        PlaybackSessionService playbackSessionService,
        HealthMonitor healthMonitor,
        QuotaService quotaService
    ) {
        transcodeRoutes(
            route,
            transcodeService,
            segmentCache,
            probeService,
            pathSecurity,
            authProviders,
            subtitleExtractor,
            virtualPathResolver,
            playbackSessionService,
            null,
            healthMonitor,
            quotaService
        );
    }

    @SuppressWarnings("LongMethod")
    public static void transcodeRoutes(
        Route route,
        TranscodeApplicationService transcodeService,
        SegmentCacheService segmentCache,
        MediaProber probeService,
        PathSecurity pathSecurity,
        List<String> authProviders,
        SubtitleExtractor subtitleExtractor,
        VirtualPathResolver virtualPathResolver,
        PlaybackSessionService playbackSessionService,
        PlaybackDecisionService playbackDecisionService,
        HealthMonitor healthMonitor,
        QuotaService quotaService
    ) {
        ObjectMapper json = NyxJson.newMapper();

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.post(
                "/api/v1/transcode",
                doc(config -> {
                    config.setDescription("Submit a new transcoding job");
                    config.request(requestDoc(request -> request.body(TranscodeRequest.class)));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_CREATED, describe("Job created"));
                        response.code(HTTP_SERVICE_UNAVAILABLE, describe("FFmpeg not available"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    if (healthMonitor != null && !healthMonitor.isFfmpegAvailable()) {
                        call.respond(
                            HTTP_SERVICE_UNAVAILABLE,
                            new ErrorResponse(
                                new ErrorDetail(ErrorCode.FFMPEG_UNAVAILABLE.name(), "FFmpeg is not available", Map.of())
                            )
                        );
                        return;
                    }
                    enforceUserRateLimit(call, quotaService);
                    TranscodeRequest request = call.receive(TranscodeRequest.class);
                    String userId = principalName(call);
                    TranscodeRequest resolvedRequest = request;
                    if (virtualPathResolver != null) {
                        Path absolutePath = resolvePathParam(request.getInputPath(), pathSecurity, virtualPathResolver);
                        resolvedRequest = copyRequestWithInputPath(request, absolutePath.toString());
                    }
                    rejectDirectFileTranscodeRepresentation(resolvedRequest);
                    PlaybackRequest playbackRequest = PlaybackRequestMapper.toPlaybackRequest(resolvedRequest);
                    TranscodeJob job;
                    if (playbackSessionService != null) {
                        PlaybackSession session = playbackSessionService.openSession(playbackRequest, userId);
                        String jobId = playbackSessionService.getSessionJobId(session.sessionId(), userId);
                        if (jobId == null) {
                            throw nyxException(
                                ErrorCode.JOB_NOT_FOUND,
                                "Compatibility playback session did not create a backing transcode job"
                            );
                        }
                        job = transcodeService.getJob(jobId);
                        if (job == null) {
                            throw nyxException(
                                ErrorCode.JOB_NOT_FOUND,
                                "Backing transcode job not found for compatibility session: " + session.sessionId()
                            );
                        }
                    } else {
                        if (playbackDecisionService == null) {
                            throw nyxException(
                                ErrorCode.INVALID_REQUEST,
                                "Playback decision service is required for compatibility transcode submission"
                            );
                        }
                        PlaybackDecision decision = playbackDecisionService.decide(playbackRequest);
                        job = transcodeService.submit(playbackRequest, decision, null, userId);
                    }
                    call.respond(HTTP_CREATED, job);
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.get(
                "/api/v1/transcode/jobs",
                doc(config -> {
                    config.setDescription("List transcoding jobs (paginated)");
                    config.request(requestDoc(request -> {
                        request.queryParameter("page", parameterDoc(param -> param.setDescription("Page number (0-based)")));
                        request.queryParameter("limit", parameterDoc(param -> param.setDescription("Page size")));
                        request.queryParameter("status", parameterDoc(param -> param.setDescription("Filter by job status")));
                    }));
                    config.response(responseDoc(response -> response.code(HTTP_OK, describe("Paginated job listing"))));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String userId = principalName(call);
                    int page = getPageParam(call, 1);
                    int limit = getLimitParam(call, 50, 200);
                    String statusParam = call.getQueryParameters().get("status");
                    String sinceParam = call.getQueryParameters().get("since");
                    Integer sinceMinutes = sinceParam == null ? null : parseInteger(sinceParam, "since");
                    JobStatus statusFilter = null;
                    if (statusParam != null) {
                        try {
                            statusFilter = JobStatus.valueOf(statusParam.toUpperCase());
                        } catch (IllegalArgumentException exception) {
                            throw nyxException(ErrorCode.INVALID_REQUEST, "Invalid status filter: " + statusParam);
                        }
                    }
                    if (statusFilter != null || sinceMinutes != null) {
                        call.respond(transcodeService.listJobsFiltered(statusFilter, sinceMinutes, page, limit, userId));
                    } else {
                        call.respond(transcodeService.listJobs(page, limit, userId));
                    }
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.post(
                "/api/v1/transcode/jobs/batch-cancel",
                doc(config -> {
                    config.setDescription("Cancel multiple transcoding jobs");
                    config.request(requestDoc(request -> request.body(BatchCancelRequest.class)));
                    config.response(responseDoc(response -> response.code(HTTP_OK, bodyDoc(BatchCancelResponse.class))));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    enforceUserRateLimit(call, quotaService);
                    String userId = principalName(call);
                    BatchCancelRequest request = call.receive(BatchCancelRequest.class);
                    List<String> cancelled = new ArrayList<>();
                    List<String> notFound = new ArrayList<>();

                    for (String jobId : request.getJobIds()) {
                        TranscodeJob job = transcodeService.getJob(jobId);
                        if (job == null) {
                            notFound.add(jobId);
                            continue;
                        }
                        try {
                            transcodeService.cancel(jobId, userId);
                            cancelled.add(jobId);
                        } catch (Throwable exception) {
                            notFound.add(jobId);
                        }
                    }

                    call.respond(new BatchCancelResponse(cancelled, notFound));
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.post(
                "/api/v1/transcode/batch-submit",
                doc(config -> {
                    config.setDescription("Submit a batch of transcoding jobs");
                    config.request(requestDoc(request -> request.body(BatchTranscodeRequest.class)));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, bodyDoc(BatchSubmitResponse.class));
                        response.code(HTTP_SERVICE_UNAVAILABLE, describe("FFmpeg not available"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    if (healthMonitor != null && !healthMonitor.isFfmpegAvailable()) {
                        call.respond(
                            HTTP_SERVICE_UNAVAILABLE,
                            new ErrorResponse(
                                new ErrorDetail(ErrorCode.FFMPEG_UNAVAILABLE.name(), "FFmpeg is not available", Map.of())
                            )
                        );
                        return;
                    }
                    enforceUserRateLimit(call, quotaService);
                    BatchTranscodeRequest request = call.receive(BatchTranscodeRequest.class);
                    for (int i = 1; i < request.getRequests().size(); i++) {
                        enforceUserRateLimit(call, quotaService);
                    }
                    String userId = principalName(call);
                    if (playbackDecisionService == null) {
                        throw nyxException(
                            ErrorCode.INVALID_REQUEST,
                            "Playback decision service is required for batch transcode submission"
                        );
                    }
                    call.respond(
                        HTTP_OK,
                        submitDecisionAwarePlaybackBatch(
                            request.getRequests(),
                            userId,
                            transcodeService,
                            playbackDecisionService,
                            pathSecurity,
                            virtualPathResolver
                        )
                    );
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.get(
                "/api/v1/transcode/batches/{batchId}",
                doc(config -> {
                    config.setDescription("Get aggregate status for a batch of transcoding jobs");
                    config.request(requestDoc(request -> request.pathParameter(
                        "batchId",
                        parameterDoc(param -> param.setDescription("Batch identifier"))
                    )));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, bodyDoc(BatchStatusResponse.class));
                        response.code(HTTP_NOT_FOUND, describe("Batch not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String userId = principalName(call);
                    String batchId = requirePathParam(call, "batchId");
                    BatchStatusResponse status = transcodeService.getBatchStatus(batchId, userId);
                    if (status == null) {
                        throw nyxException(ErrorCode.JOB_NOT_FOUND, "Batch not found: " + batchId);
                    }
                    call.respond(HTTP_OK, status);
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.delete(
                "/api/v1/transcode/batches/{batchId}",
                doc(config -> {
                    config.setDescription("Cancel all active jobs in a batch");
                    config.request(requestDoc(request -> request.pathParameter(
                        "batchId",
                        parameterDoc(param -> param.setDescription("Batch identifier"))
                    )));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, bodyDoc(BatchCancelResponse.class));
                        response.code(HTTP_NOT_FOUND, describe("Batch not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    enforceUserRateLimit(call, quotaService);
                    String userId = principalName(call);
                    String batchId = requirePathParam(call, "batchId");
                    BatchCancelResponse result = transcodeService.cancelBatch(batchId, userId);
                    if (result == null) {
                        throw nyxException(ErrorCode.JOB_NOT_FOUND, "Batch not found: " + batchId);
                    }
                    call.respond(HTTP_OK, result);
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.get(
                "/api/v1/transcode/jobs/{jobId}",
                doc(config -> {
                    config.setDescription("Get transcoding job details");
                    config.request(requestDoc(request -> request.pathParameter(
                        "jobId",
                        parameterDoc(param -> param.setDescription("Job identifier"))
                    )));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, describe("Job details"));
                        response.code(HTTP_NOT_FOUND, describe("Job not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String userId = principalName(call);
                    String jobId = requirePathParam(call, "jobId");
                    TranscodeJob job = transcodeService.getJob(jobId);
                    if (job == null) {
                        throw nyxException(ErrorCode.JOB_NOT_FOUND, "Job not found: " + jobId);
                    }
                    checkJobOwnership(job, userId);
                    call.respond(job);
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.get(
                "/api/v1/transcode/jobs/{jobId}/manifest.mpd",
                doc(config -> {
                    config.setDescription("Get DASH manifest for a transcoding job");
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, describe("MPEG-DASH MPD manifest"));
                        response.code(HTTP_NOT_FOUND, describe("Job not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String jobId = requirePathParam(call, "jobId");
                    requireOwnedJob(transcodeService, call, jobId);
                    String manifest = transcodeService.getManifestMpd(jobId);
                    if (manifest == null) {
                        throw nyxException(ErrorCode.JOB_NOT_FOUND, "Job not found: " + jobId);
                    }
                    call.respondText(manifest, ContentType.Companion.parse(MediaTypes.DASH_MPD));
                })
            );

            authenticatedRoute.get(
                "/api/v1/transcode/jobs/{jobId}/master.m3u8",
                doc(config -> {
                    config.setDescription("Get HLS master playlist for a transcoding job");
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, describe("HLS master playlist"));
                        response.code(HTTP_NOT_FOUND, describe("Job not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String jobId = requirePathParam(call, "jobId");
                    requireOwnedJob(transcodeService, call, jobId);
                    String manifest = transcodeService.getManifestM3u8(jobId);
                    if (manifest == null) {
                        throw nyxException(ErrorCode.JOB_NOT_FOUND, "Job not found: " + jobId);
                    }
                    call.respondText(manifest, ContentType.Companion.parse(MediaTypes.HLS_M3U8));
                })
            );

            authenticatedRoute.get(
                "/api/v1/transcode/jobs/{jobId}/{repId}.m3u8",
                doc(config -> {
                    config.setDescription("Get HLS media playlist for a variant stream");
                    config.request(requestDoc(request -> {
                        request.pathParameter("jobId", parameterDoc(param -> param.setDescription("Job identifier")));
                        request.pathParameter(
                            "repId",
                            parameterDoc(param -> param.setDescription("Representation ID, e.g. 'video' or '1280x720'"))
                        );
                    }));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, describe("HLS media playlist"));
                        response.code(HTTP_NOT_FOUND, describe("Job not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String jobId = requirePathParam(call, "jobId");
                    String repId = requirePathParam(call, "repId");
                    requireOwnedJob(transcodeService, call, jobId);
                    String playlist = transcodeService.getHlsMediaPlaylist(jobId, repId);
                    if (playlist == null) {
                        throw nyxException(ErrorCode.JOB_NOT_FOUND, "Job not found: " + jobId);
                    }
                    call.respondText(playlist, ContentType.Companion.parse(MediaTypes.HLS_M3U8));
                })
            );

            authenticatedRoute.get(
                "/api/v1/transcode/jobs/{jobId}/segments/{name}",
                doc(config -> {
                    config.setDescription("Serve a media segment from a transcoding job");
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, describe("Segment data"));
                        response.code(HTTP_ACCEPTED, describe("Segment not ready yet"));
                        response.code(HTTP_NOT_FOUND, describe("Job not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String jobId = requirePathParam(call, "jobId");
                    String name = requirePathParam(call, "name");
                    if (name.contains("/") || name.contains("\\") || name.contains("..")) {
                        throw nyxException(ErrorCode.INVALID_REQUEST, "Invalid segment name");
                    }
                    requireOwnedJob(transcodeService, call, jobId);
                    Path outputDir = transcodeService.getSegmentOutputDir(jobId);
                    if (outputDir == null) {
                        throw nyxException(ErrorCode.JOB_NOT_FOUND, "Job not found: " + jobId);
                    }
                    Path segmentPath = outputDir.resolve(name);
                    if (!segmentPath.normalize().startsWith(outputDir.normalize())) {
                        throw nyxException(ErrorCode.INVALID_REQUEST, "Invalid segment name");
                    }

                    Path acquired = segmentCache.acquire(segmentPath);
                    if (acquired != null && Files.exists(acquired)) {
                        try {
                            String mimeType = defaultMimeType(name);
                            call.respondFile(acquired, mimeType);
                        } finally {
                            segmentCache.release(segmentPath);
                        }
                    } else if (Files.exists(segmentPath)) {
                        call.respondFile(segmentPath, defaultMimeType(name));
                    } else {
                        call.getResponse().header(HttpHeaders.RetryAfter, "2");
                        call.respond(HTTP_ACCEPTED, Map.of("status", "pending", "retry_after", 2));
                    }
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.sse(
                "/api/v1/transcode/jobs/{jobId}/progress",
                preflight(scope -> {
                    RoutingCall call = scope.getCall();
                    String userId = principalName(call);
                    String jobId = requirePathParam(call, "jobId");
                    TranscodeJob job = transcodeService.getJob(jobId);
                    if (job == null) {
                        throw nyxException(ErrorCode.JOB_NOT_FOUND, "Job not found: " + jobId);
                    }
                    checkJobOwnership(job, userId);
                    if (transcodeService.eventFlow(jobId) == null) {
                        throw nyxException(ErrorCode.JOB_NOT_FOUND, "Job not found: " + jobId);
                    }
                }),
                sseHandler(scope -> {
                    RoutingCall call = scope.getCall();
                    String jobId = requirePathParam(call, "jobId");
                    Flow.Publisher<JobEvent> eventPublisher = transcodeService.eventFlow(jobId);
                    if (eventPublisher == null) {
                        throw nyxException(ErrorCode.JOB_NOT_FOUND, "Job not found: " + jobId);
                    }

                    CountDownLatch streamClosed = new CountDownLatch(1);
                    eventPublisher.subscribe(new Flow.Subscriber<>() {
                        private Flow.Subscription subscription;

                        @Override
                        public void onSubscribe(Flow.Subscription subscription) {
                            this.subscription = subscription;
                            subscription.request(Long.MAX_VALUE);
                        }

                        @Override
                        public void onNext(JobEvent event) {
                            try {
                                String eventType;
                                String data;
                                if (event instanceof JobEvent.Progress) {
                                    eventType = "progress";
                                    data = json.writeValueAsString(event);
                                } else if (event instanceof JobEvent.Segment) {
                                    eventType = "segment";
                                    data = json.writeValueAsString(event);
                                } else if (event instanceof JobEvent.Complete) {
                                    eventType = "complete";
                                    data = json.writeValueAsString(event);
                                } else if (event instanceof JobEvent.Retry) {
                                    eventType = "retry";
                                    data = json.writeValueAsString(event);
                                } else if (event instanceof JobEvent.Error) {
                                    eventType = "error";
                                    data = json.writeValueAsString(event);
                                } else {
                                    throw new IllegalStateException("Unknown job event: " + event);
                                }
                                scope.send(new ServerSentEvent(data, eventType));
                                if (event instanceof JobEvent.Complete || event instanceof JobEvent.Error) {
                                    if (subscription != null) {
                                        subscription.cancel();
                                    }
                                    streamClosed.countDown();
                                }
                            } catch (Exception exception) {
                                onError(exception);
                            }
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            String message = throwable.getMessage() == null ? "stream failed" : throwable.getMessage();
                            scope.send(new ServerSentEvent("{\"error\":\"" + escapeJson(message) + "\"}", "error"));
                            streamClosed.countDown();
                        }

                        @Override
                        public void onComplete() {
                            streamClosed.countDown();
                        }
                    });

                    try {
                        streamClosed.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(exception);
                    }
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.get(
                "/api/v1/transcode/jobs/{jobId}/logs",
                doc(config -> {
                    config.setDescription("Get FFmpeg stderr logs for a transcoding job");
                    config.response(responseDoc(response -> response.code(HTTP_OK, describe("Log text"))));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String userId = principalName(call);
                    String jobId = requirePathParam(call, "jobId");
                    TranscodeJob job = transcodeService.getJob(jobId);
                    if (job == null) {
                        throw nyxException(ErrorCode.JOB_NOT_FOUND, "Job not found: " + jobId);
                    }
                    checkJobOwnership(job, userId);
                    call.respondText(transcodeService.getLogs(jobId), ContentType.Companion.parse("text/plain"));
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.post(
                "/api/v1/transcode/jobs/{jobId}/seek",
                doc(config -> {
                    config.setDescription("Seek to a timestamp by cancelling and resubmitting the job");
                    config.request(requestDoc(request -> request.body(SeekRequest.class)));
                    config.response(responseDoc(response -> response.code(HTTP_CREATED, describe("New job from seek position"))));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    enforceUserRateLimit(call, quotaService);
                    String userId = principalName(call);
                    String jobId = requirePathParam(call, "jobId");
                    SeekRequest seekRequest = call.receive(SeekRequest.class);
                    transcodeService.cancel(jobId, userId);

                    TranscodeJob originalJob = transcodeService.getJob(jobId);
                    if (originalJob == null) {
                        throw nyxException(ErrorCode.JOB_NOT_FOUND, "Job not found: " + jobId);
                    }

                    TranscodeRequest newRequest = createSeekRequest(originalJob, seekRequest.timestamp_secs());
                    call.respond(HTTP_CREATED, transcodeService.submit(newRequest, null, userId));
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.delete(
                "/api/v1/transcode/jobs/{jobId}",
                doc(config -> {
                    config.setDescription("Cancel a transcoding job");
                    config.response(responseDoc(response -> response.code(HTTP_NO_CONTENT, describe("Job cancelled"))));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    enforceUserRateLimit(call, quotaService);
                    transcodeService.cancel(requirePathParam(call, "jobId"), principalName(call));
                    call.respond(HTTP_NO_CONTENT);
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.post(
                "/api/v1/transcode/jobs/{jobId}/subtitles/{trackIndex}",
                doc(config -> {
                    config.setDescription("Extract a subtitle track as WebVTT");
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, describe("Extraction result with file path"));
                        response.code(HTTP_NOT_FOUND, describe("Job not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    enforceUserRateLimit(call, quotaService);
                    String jobId = requirePathParam(call, "jobId");
                    int trackIndex = parseInteger(requirePathParam(call, "trackIndex"), "trackIndex");
                    String userId = principalName(call);
                    TranscodeJob job = transcodeService.getJob(jobId);
                    if (job == null) {
                        throw nyxException(ErrorCode.JOB_NOT_FOUND, "Job not found: " + jobId);
                    }
                    checkJobOwnership(job, userId);
                    if (subtitleExtractor == null) {
                        throw nyxException(ErrorCode.INVALID_REQUEST, "Subtitle extraction not configured");
                    }

                    Path outputDir = transcodeService.getSegmentOutputDir(jobId);
                    if (outputDir == null) {
                        outputDir = Path.of(System.getProperty("java.io.tmpdir"), "nyx-subtitles-" + jobId);
                        createDirectories(outputDir);
                    }

                    Path vttPath;
                    try {
                        vttPath = subtitleExtractor.extractWebVtt(Path.of(job.getInputPath()), trackIndex, outputDir);
                    } catch (Throwable throwable) {
                        throw nyxException(
                            ErrorCode.TRANSCODE_FAILED,
                            "Subtitle extraction failed: " + defaultMessage(throwable, "unknown error")
                        );
                    }

                    call.respond(Map.of("path", vttPath.toString(), "track_index", trackIndex));
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.get(
                "/api/v1/transcode/jobs/{jobId}/subtitles/{trackIndex}.m3u8",
                doc(config -> {
                    config.setDescription("Get HLS subtitle media playlist (RFC 8216 §4.3.4.1 compliant)");
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, describe("HLS subtitle media playlist"));
                        response.code(HTTP_NOT_FOUND, describe("Job not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String jobId = requirePathParam(call, "jobId");
                    int trackIndex = parseInteger(requirePathParam(call, "trackIndex"), "trackIndex");
                    requireOwnedJob(transcodeService, call, jobId);
                    String playlist = transcodeService.getSubtitlePlaylist(jobId, trackIndex);
                    if (playlist == null) {
                        throw nyxException(ErrorCode.JOB_NOT_FOUND, "Job not found: " + jobId);
                    }
                    call.respondText(playlist, ContentType.Companion.parse("application/vnd.apple.mpegurl"));
                })
            );

            authenticatedRoute.get(
                "/api/v1/transcode/jobs/{jobId}/subtitles/{trackIndex}",
                doc(config -> {
                    config.setDescription("Serve extracted WebVTT subtitle file");
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, describe("WebVTT subtitle file"));
                        response.code(HTTP_NOT_FOUND, describe("Subtitle file not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String jobId = requirePathParam(call, "jobId");
                    int trackIndex = parseInteger(requirePathParam(call, "trackIndex"), "trackIndex");
                    if (subtitleExtractor == null) {
                        throw nyxException(ErrorCode.INVALID_REQUEST, "Subtitle extraction not configured");
                    }

                    TranscodeJob job = requireOwnedJob(transcodeService, call, jobId);
                    Path outputDir = transcodeService.getSegmentOutputDir(jobId);
                    if (outputDir == null) {
                        outputDir = Path.of(System.getProperty("java.io.tmpdir"), "nyx-subtitles-" + jobId);
                        createDirectories(outputDir);
                    }

                    Path vttPath = outputDir.resolve("subtitle_" + trackIndex + ".vtt");
                    if (!Files.exists(vttPath)) {
                        try {
                            subtitleExtractor.extractWebVtt(Path.of(job.getInputPath()), trackIndex, outputDir);
                        } catch (Throwable throwable) {
                            throw nyxException(
                                ErrorCode.TRANSCODE_FAILED,
                                "Subtitle extraction failed: " + defaultMessage(throwable, "unknown error")
                            );
                        }
                    }

                    if (Files.exists(vttPath)) {
                        call.respondBytes(readAllBytes(vttPath), ContentType.Companion.parse("text/vtt"));
                    } else {
                        throw nyxException(ErrorCode.FILE_NOT_FOUND, "Subtitle file not found after extraction");
                    }
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.get(
                "/api/v1/media/probe",
                doc(config -> {
                    config.setDescription("Probe a media file for metadata (codec, duration, streams)");
                    config.request(requestDoc(request -> request.queryParameter(
                        "path",
                        parameterDoc(param -> param.setDescription("Path to the media file"))
                    )));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, describe("Probe result with media metadata"));
                        response.code(HTTP_BAD_REQUEST, describe("Invalid path"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String path = call.getRequest().getQueryParameters().get("path");
                    if (path == null) {
                        throw nyxException(ErrorCode.INVALID_REQUEST, "Missing path parameter");
                    }
                    Path validPath = virtualPathResolver != null
                        ? resolvePathParam(path, pathSecurity, virtualPathResolver)
                        : pathSecurity.validate(path);
                    try {
                        call.respond(MediaProberInterop.probeCachedOrThrow(probeService, validPath));
                    } catch (Throwable throwable) {
                        throw nyxException(ErrorCode.PROBE_FAILED, "Failed to probe: " + defaultMessage(throwable, "unknown error"));
                    }
                })
            );
        });
    }

    private static void checkJobOwnership(TranscodeJob job, String userId) {
        if (userId != null && job.getOwner() != null && !job.getOwner().equals(userId)) {
            throw nyxException(ErrorCode.JOB_NOT_FOUND, "Job not found: " + job.getId());
        }
    }

    private static TranscodeJob requireOwnedJob(
        TranscodeApplicationService transcodeService,
        RoutingCall call,
        String jobId
    ) {
        TranscodeJob job = transcodeService.getJob(jobId);
        if (job == null) {
            throw nyxException(ErrorCode.JOB_NOT_FOUND, "Job not found: " + jobId);
        }
        checkJobOwnership(job, principalName(call));
        return job;
    }

    private static TranscodeRequest copyRequestWithInputPath(TranscodeRequest request, String inputPath) {
        return new TranscodeRequest(
            inputPath,
            request.getStartTimeSecs(),
            request.getProfile(),
            request.getRepresentation(),
            request.getRepresentations(),
            request.getSubtitleMode(),
            request.getBurnSubtitleTrack(),
            request.getAudioTracks(),
            request.getHwaccel(),
            request.getExecutionMode(),
            buildSpecKey(
                inputPath,
                request.getStartTimeSecs(),
                request.getProfile(),
                request.getRepresentation(),
                request.getExecutionMode(),
                request.getRepresentations(),
                request.getSubtitleMode(),
                request.getBurnSubtitleTrack(),
                request.getAudioTracks(),
                request.getHwaccel()
            )
        );
    }

    private static BatchSubmitResponse submitDecisionAwarePlaybackBatch(
        List<TranscodeRequest> requests,
        String owner,
        TranscodeApplicationService transcodeService,
        PlaybackDecisionService playbackDecisionService,
        PathSecurity pathSecurity,
        VirtualPathResolver virtualPathResolver
    ) {
        String batchId = UUID.randomUUID().toString().substring(0, 8);
        List<TranscodeJob> submitted = new ArrayList<>();
        List<BatchJobError> errors = new ArrayList<>();
        for (TranscodeRequest request : requests) {
            try {
                TranscodeRequest resolvedRequest = validateBatchRequestPath(request, pathSecurity, virtualPathResolver);
                rejectDirectFileTranscodeRepresentation(resolvedRequest);
                PlaybackRequest playbackRequest = PlaybackRequestMapper.toPlaybackRequest(resolvedRequest);
                PlaybackDecision decision = playbackDecisionService.decide(playbackRequest);
                submitted.add(transcodeService.submit(playbackRequest, decision, batchId, owner));
            } catch (Exception exception) {
                errors.add(batchError(request, exception));
            }
        }
        return new BatchSubmitResponse(batchId, submitted, errors);
    }

    private static TranscodeRequest validateBatchRequestPath(
        TranscodeRequest request,
        PathSecurity pathSecurity,
        VirtualPathResolver virtualPathResolver
    ) {
        if (virtualPathResolver == null) {
            pathSecurity.validate(request.getInputPath());
            return request;
        }
        Path absolutePath = resolvePathParam(request.getInputPath(), pathSecurity, virtualPathResolver);
        return copyRequestWithInputPath(request, absolutePath.toString());
    }

    private static BatchJobError batchError(TranscodeRequest request, Exception exception) {
        if (exception instanceof NyxException nyxException) {
            return new BatchJobError(request.getInputPath(), nyxException.getErrorCode().name());
        }
        return new BatchJobError(request.getInputPath(), defaultMessage(exception, "UNKNOWN"));
    }

    private static void rejectDirectFileTranscodeRepresentation(TranscodeRequest request) {
        if (request.getRepresentation() == StreamRepresentation.DIRECT_FILE) {
            throw nyxException(
                ErrorCode.INVALID_REQUEST,
                "Direct file representation is only valid for direct-play playback delivery"
            );
        }
    }

    private static TranscodeRequest createSeekRequest(TranscodeJob job, double startTimeSecs) {
        List<com.nyx.transcode.contracts.TranscodeRepresentation> representations =
            job.getRepresentations().isEmpty() ? null : job.getRepresentations();
        TranscodeExecutionMode executionMode = job.getExecutionMode();
        return new TranscodeRequest(
            job.getInputPath(),
            startTimeSecs,
            job.getProfile(),
            job.getRepresentation(),
            representations,
            "extract",
            null,
            "all",
            "auto",
            executionMode,
            buildSpecKey(
                job.getInputPath(),
                startTimeSecs,
                job.getProfile(),
                job.getRepresentation(),
                executionMode,
                representations,
                "extract",
                null,
                "all",
                "auto"
            )
        );
    }

    private static String buildSpecKey(
        String inputPath,
        Double startTimeSecs,
        String profile,
        StreamRepresentation representation,
        TranscodeExecutionMode executionMode,
        List<com.nyx.transcode.contracts.TranscodeRepresentation> representations,
        String subtitleMode,
        Integer burnSubtitleTrack,
        String audioTracks,
        String hwaccel
    ) {
        return TranscodeContracts.buildTranscodeSpecKey(
            inputPath,
            startTimeSecs,
            profile,
            representation,
            executionMode,
            representations,
            subtitleMode,
            burnSubtitleTrack,
            audioTracks,
            hwaccel
        );
    }

    private static int parseInteger(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "Invalid or missing " + name);
        }
    }

    private static String defaultMimeType(String path) {
        String mimeType = MediaTypes.INSTANCE.mimeTypeForPath(path);
        return mimeType == null ? "application/octet-stream" : mimeType;
    }

    private static String requirePathParam(RoutingCall call, String name) {
        String value = call.getParameters().get(name);
        if (value == null) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "Missing " + name);
        }
        return value;
    }

    private static String principalName(RoutingCall call) {
        UserIdPrincipal principal = call.principal(UserIdPrincipal.class);
        return principal == null ? null : principal.getName();
    }

    private static String defaultMessage(Throwable throwable, String fallback) {
        return throwable.getMessage() == null ? fallback : throwable.getMessage();
    }

    private static void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Failed to create directory: " + path, exception);
        }
    }

    private static byte[] readAllBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Failed to read file: " + path, exception);
        }
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void optionalAuth(Route route, List<String> authProviders, RouteRegistrar registrar) {
        registrar.accept(route.withAuth(AuthMode.OPTIONAL, authProviders));
    }

    private static Consumer<OpenApiRouteConfig> doc(RouteDoc doc) {
        return doc::accept;
    }

    private static Consumer<com.nyx.http.RequestDoc> requestDoc(RequestDocBlock block) {
        return block::accept;
    }

    private static Consumer<com.nyx.http.ResponseCollection> responseDoc(ResponseDocBlock block) {
        return block::accept;
    }

    private static Consumer<ParameterDoc> parameterDoc(ParameterDocBlock block) {
        return block::accept;
    }

    private static Consumer<com.nyx.http.ResponseDoc> describe(String description) {
        return response -> response.setDescription(description);
    }

    private static Consumer<com.nyx.http.ResponseDoc> bodyDoc(Class<?> type) {
        return response -> response.body(type);
    }

    private static Consumer<RouteHandlerScope> handler(RouteHandler handler) {
        return handler::accept;
    }

    private static Consumer<RouteHandlerScope> preflight(RouteHandler handler) {
        return handler::accept;
    }

    private static Consumer<SseRouteHandlerScope> sseHandler(SseHandler handler) {
        return handler::accept;
    }

    private static RuntimeException nyxException(ErrorCode code, String message) {
        return sneakyThrow(new NyxException(code, message, Map.of(), null));
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    @FunctionalInterface
    private interface RouteRegistrar {
        void accept(Route route);
    }

    @FunctionalInterface
    private interface RouteDoc {
        void accept(OpenApiRouteConfig config);
    }

    @FunctionalInterface
    private interface RequestDocBlock {
        void accept(com.nyx.http.RequestDoc request);
    }

    @FunctionalInterface
    private interface ResponseDocBlock {
        void accept(com.nyx.http.ResponseCollection response);
    }

    @FunctionalInterface
    private interface ParameterDocBlock {
        void accept(ParameterDoc parameter);
    }

    @FunctionalInterface
    private interface RouteHandler {
        void accept(RouteHandlerScope scope);
    }

    @FunctionalInterface
    private interface SseHandler {
        void accept(SseRouteHandlerScope scope);
    }
}

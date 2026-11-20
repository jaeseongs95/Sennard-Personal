// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.updates;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The release source that asks the repository's tag listing (GitHub's git data API). It runs outside every transaction: the answer is only data for the one that records it. */
@Component
class GithubReleaseSource implements ReleaseSource {
    private static final Logger LOG = LoggerFactory.getLogger(GithubReleaseSource.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_TAG_REFERENCES = 10_000;
    private static final int MAX_TAG_CHAIN = 5;
    private static final Pattern STABLE_TAG = Pattern.compile("^refs/tags/v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$");
    private static final Pattern COMMIT = Pattern.compile("^[a-f0-9]{40}$");
    private static final String TAG_OBJECT_PREFIX = "https://api.github.com/repos/jaeseongs95/Sennard-Personal/git/tags/";

    /** A check that found a fault in the answer; it is turned into {@link Failed} at the edge of {@link #ask}. */
    private static final class UpdateCheckFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final PluginUpdateService.Reason reason;
        /** The HTTP status the repository answered with, for the failures that are one (the status is the only thing that tells a missing repository from a rate limit); 0 for the others. */
        final int status;

        UpdateCheckFailure(PluginUpdateService.Reason reason) {
            this(reason, 0);
        }

        UpdateCheckFailure(PluginUpdateService.Reason reason, int status) {
            super(reason.name(), null, false, false);
            this.reason = reason;
            this.status = status;
        }
    }

    /** The kinds of object a git reference points at, as GitHub names them. */
    enum GitObjectType {
        COMMIT, TAG, TREE, BLOB
    }

    /** One object of GitHub's git data API; all three members are there on every answer this service reads. */
    record GitObject(String sha, @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_VALUES) GitObjectType type, String url) {
        boolean complete() {
            return sha != null && type != null && url != null;
        }
    }

    /** An entry of the tag listing ({@code git/matching-refs}). */
    record GitRef(String ref, GitObject object) {
    }

    /** What an annotated tag's own object answers: the object it points at. */
    record GitTag(GitObject object) {
    }

    private final JsonMapper mapper;
    private final String version;
    private final String tagsApi;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(REQUEST_TIMEOUT).build();

    GithubReleaseSource(JsonMapper mapper, McpServerProperties server, SennardProperties properties) {
        this.mapper = mapper;
        this.version = server.getVersion();
        this.tagsApi = properties.updates().tagsApi();
    }

    @Override
    public Answer ask() {
        return answer(base(tagsApi));
    }

    @Override
    public Answer askIfChanged(String etag) {
        return answer(base(tagsApi).header("If-None-Match", etag));
    }

    private Answer answer(HttpRequest.Builder request) {
        try {
            return fetch(request);
        } catch (UpdateCheckFailure failure) {
            // The status code and the reason only: the last check's published status carries the reason, and nothing that names the request (its address, its headers) is logged.
            LOG.warn("The release check recorded a failure: reason={} httpStatus={}", failure.reason, failure.status == 0 ? "none" : failure.status);
            return new Failed(failure.reason);
        } catch (IOException | RuntimeException failure) {
            // Boundary (external service): the repository's answer is untrusted input of the network, and any failure of asking is recorded as NETWORK; logged with its cause.
            LOG.warn("The release check could not ask the repository", failure);
            return new Failed(PluginUpdateService.Reason.NETWORK);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Failed(PluginUpdateService.Reason.TIMEOUT);
        }
    }

    private Answer fetch(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 304) {
            response.body().close();
            return new NotModified();
        }
        List<GitRef> refs = readJson(response, new TypeReference<List<GitRef>>() { });
        if (refs.size() > MAX_TAG_REFERENCES) {
            throw new UpdateCheckFailure(PluginUpdateService.Reason.INVALID_RESPONSE);
        }
        record Tag(PluginUpdateService.StableVersion version, GitObject object) {
        }
        List<Tag> stable = new ArrayList<>();
        for (GitRef item : refs) {
            Matcher match = item.ref() == null ? null : STABLE_TAG.matcher(item.ref());
            if (match != null && match.matches() && item.object() != null && item.object().complete()) {
                stable.add(new Tag(PluginUpdateService.StableVersion.of(match), item.object()));
            }
        }
        if (stable.isEmpty()) {
            throw new UpdateCheckFailure(PluginUpdateService.Reason.NO_STABLE_TAG);
        }
        Tag latest = stable.stream().max(Comparator.comparing(Tag::version)).orElseThrow();
        String commit = resolveCommit(latest.object());
        String version = latest.version().toString();
        return response.headers().firstValue("etag").<Latest>map(etag -> new Tagged(version, commit, etag)).orElseGet(() -> new Untagged(version, commit));
    }

    /** Follows annotated tags (a tag object points at the next object) until the commit. */
    private String resolveCommit(GitObject initial) throws IOException, InterruptedException {
        GitObject current = initial;
        for (int depth = 0; depth < MAX_TAG_CHAIN; depth++) {
            if (current.type() == GitObjectType.COMMIT && COMMIT.matcher(current.sha()).matches()) {
                return current.sha();
            }
            if (current.type() != GitObjectType.TAG || !current.url().startsWith(TAG_OBJECT_PREFIX)) {
                throw new UpdateCheckFailure(PluginUpdateService.Reason.INVALID_RESPONSE);
            }
            GitTag tag = readJson(http.send(base(current.url()).build(), HttpResponse.BodyHandlers.ofInputStream()), new TypeReference<GitTag>() { });
            current = tag.object();
            if (current == null || !current.complete()) {
                throw new UpdateCheckFailure(PluginUpdateService.Reason.INVALID_RESPONSE);
            }
        }
        throw new UpdateCheckFailure(PluginUpdateService.Reason.INVALID_RESPONSE);
    }

    private HttpRequest.Builder base(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).header("Accept", "application/vnd.github+json")
                .header("User-Agent", "%s/%s".formatted(PluginUpdateService.PLUGIN_ID, version));
    }

    /** The answer read once, within the size limit, straight into the record; a body that is not that JSON is an invalid response, not an error of the server. */
    private <T> T readJson(HttpResponse<InputStream> response, TypeReference<T> type) throws IOException {
        try (InputStream in = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new UpdateCheckFailure(PluginUpdateService.Reason.HTTP, response.statusCode());
            }
            byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new UpdateCheckFailure(PluginUpdateService.Reason.INVALID_RESPONSE);
            }
            try {
                T value = mapper.readValue(bytes, type);
                if (value == null) {
                    throw new UpdateCheckFailure(PluginUpdateService.Reason.INVALID_RESPONSE);
                }
                return value;
            } catch (JacksonException notThatJson) {
                LOG.debug("The repository's answer is not the JSON expected", notThatJson);
                throw new UpdateCheckFailure(PluginUpdateService.Reason.INVALID_RESPONSE);
            }
        }
    }
}

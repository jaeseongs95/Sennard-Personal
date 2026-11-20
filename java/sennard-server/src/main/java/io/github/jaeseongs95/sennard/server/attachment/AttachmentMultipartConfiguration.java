// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.attachment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import io.github.jaeseongs95.sennard.app.attachment.ArtifactErrors;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentService;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentSettings;
import io.github.jaeseongs95.sennard.app.attachment.StorageFolders;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.web.FilterRefusals;
import io.github.jaeseongs95.sennard.server.web.RefusedRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Servlet-standard multipart parsing, disk spooling and cleanup; current signed bounds are checked before parsing. */
@Configuration(proxyBeanMethods = false)
public class AttachmentMultipartConfiguration {
    @Bean
    MultipartConfigElement multipartConfigElement(SennardProperties properties, StorageFolders folders) {
        Path temp = properties.stateDir().resolve("attachment-transfer").toAbsolutePath().normalize();
        folders.ensureOwnerOnlyFolder(temp);
        // Servlet multipart limits are fixed at registration. The preceding filter enforces the current signed limits per request.
        return new MultipartConfigElement(temp.toString(), -1, -1, 0);
    }

    @Bean(name = "multipartResolver")
    StandardServletMultipartResolver multipartResolver() {
        var resolver = new StandardServletMultipartResolver();
        resolver.setStrictServletCompliance(true);
        resolver.setResolveLazily(true);
        return resolver;
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> attachmentMultipartGuard(ObjectProvider<AttachmentSettings> settings,
            ObjectProvider<AttachmentService> attachments, MultipartConfigElement config, FilterRefusals refusals) {
        Set<String> active = ConcurrentHashMap.newKeySet();
        var filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
                String uploadId = request.getHeader(AttachmentUploadController.UPLOAD_HEADER);
                boolean claimed = false;
                try {
                    if (!HttpMethod.POST.matches(request.getMethod())) throw refused(HttpStatus.METHOD_NOT_ALLOWED, "UPLOAD_METHOD", "Use POST for file uploads.");
                    MediaType type;
                    try { type = MediaType.parseMediaType(request.getContentType() == null ? "" : request.getContentType()); }
                    catch (IllegalArgumentException invalid) { throw refused(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UPLOAD_CONTENT_TYPE", "Use multipart/form-data for file uploads."); }
                    if (!type.isCompatibleWith(MediaType.MULTIPART_FORM_DATA)) throw refused(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UPLOAD_CONTENT_TYPE", "Use multipart/form-data for file uploads.");
                    long length = request.getContentLengthLong();
                    if (length < 0) throw refused(HttpStatus.LENGTH_REQUIRED, "UPLOAD_LENGTH_REQUIRED", "File uploads require Content-Length.");
                    var policy = settings.getObject().current();
                    if (length > policy.maxFileBytes() + 65536) throw refused(HttpStatus.CONTENT_TOO_LARGE, "ARTIFACT_LIMIT", "The upload exceeds the configured file limit.");
                    if (uploadId == null) throw refused(HttpStatus.BAD_REQUEST, "UPLOAD_ID_REQUIRED", "The upload ID is required.");
                    var status = attachments.getObject().uploadStatus(uploadId).orElseThrow(ArtifactErrors::notFound);
                    if (status.size() > policy.maxFileBytes()) throw refused(HttpStatus.CONTENT_TOO_LARGE, "ARTIFACT_LIMIT", "The upload exceeds the configured file limit.");
                    if (length > status.size() + 65536) throw refused(HttpStatus.CONTENT_TOO_LARGE, "ARTIFACT_LIMIT", "The upload exceeds its declared file size.");
                    long usable;
                    try { usable = Files.getFileStore(Path.of(config.getLocation())).getUsableSpace(); }
                    catch (IOException failure) { throw ArtifactErrors.storageIo(failure); }
                    if (usable - policy.diskMarginBytes() < status.size()) throw ArtifactErrors.storageFull(status.size(), usable, policy.diskMarginBytes());
                    claimed = active.add(uploadId);
                    if (!claimed) throw ArtifactErrors.busy();
                } catch (SennardException | RefusedRequest refusal) {
                    refusals.refuse(request, response, refusal);
                    return;
                }
                try { chain.doFilter(request, response); }
                finally { if (claimed) active.remove(uploadId); }
            }
        };
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(filter);
        registration.setName("attachmentMultipartGuard");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.addUrlPatterns(AttachmentUploadController.PATH);
        return registration;
    }

    private static RefusedRequest refused(HttpStatus status, String code, String sentence) {
        return new RefusedRequest(status, code, sentence, Map.of());
    }
}

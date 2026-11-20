// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.web;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/** The guard's check of a request's body: a body is UTF-8 JSON and within the limit of its port; only an accepted request reads it. */
final class BodyGuardFilter extends GuardStage {
    private static final Logger LOG = LoggerFactory.getLogger(BodyGuardFilter.class);

    private final AdminPageAddress address;
    private final long maxBodyBytes;
    private final long adminMaxBodyBytes;

    /** @param adminMaxBodyBytes the body limit of the admin page's port, which only carries small signed records */
    BodyGuardFilter(long maxBodyBytes, long adminMaxBodyBytes, AdminPageAddress address, FilterRefusals refusals) {
        super(refusals);
        this.address = address;
        this.maxBodyBytes = maxBodyBytes;
        this.adminMaxBodyBytes = adminMaxBodyBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        // This exact route has its own bounded multipart guard before this JSON guard.
        if (request.getRequestURI().equals(io.github.jaeseongs95.sennard.server.attachment.AttachmentUploadController.PATH)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest forwarded = request;
        if (hasBody(request)) {
            if (!isUtf8Json(request.getContentType())) {
                reject(request, response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, GuardRefusal.CONTENT_TYPE_NOT_JSON);
                return;
            }
            long limit = address.owns(request.getLocalPort()) ? adminMaxBodyBytes : maxBodyBytes;
            long declared = request.getContentLengthLong();
            if (declared > limit) {
                reject(request, response, HttpStatus.CONTENT_TOO_LARGE, GuardRefusal.BODY_TOO_LARGE);
                return;
            }
            if (declared < 0) {
                // A chunked body's size is known only by reading it. Reading it here is what lets the answer be 413: past this filter the
                // transport maps any read failure to 400 or 500.
                byte[] body = request.getInputStream().readNBytes(Math.toIntExact(limit + 1));
                if (body.length > limit) {
                    reject(request, response, HttpStatus.CONTENT_TOO_LARGE, GuardRefusal.BODY_TOO_LARGE);
                    return;
                }
                forwarded = new BufferedBodyRequest(request, body);
            }
        }
        chain.doFilter(forwarded, response);
    }

    private static boolean hasBody(HttpServletRequest request) {
        return request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
    }

    private static boolean isUtf8Json(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType media = MediaType.parseMediaType(contentType);
            return media.equalsTypeAndSubtype(MediaType.APPLICATION_JSON)
                    && (media.getCharset() == null || StandardCharsets.UTF_8.equals(media.getCharset()));
        } catch (IllegalArgumentException unusable) {
            // Spring has no parse that reports a malformed media type as a value; the header is the client's input and a malformed one is a refusal.
            LOG.debug("The Content-Type header is not a media type", unusable);
            return false;
        }
    }

    /** A chunked request's body, already read and within the limit. */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bytes = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return bytes.read();
                }

                @Override
                public int read(byte[] target, int offset, int length) {
                    return bytes.read(target, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return bytes.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("The server does not read request bodies asynchronously.");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}

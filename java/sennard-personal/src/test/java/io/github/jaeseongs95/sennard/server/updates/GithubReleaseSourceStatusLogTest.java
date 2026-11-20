// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.updates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * A repository that answers the tag listing with an HTTP error is recorded as {@code HTTP}, and the status it answered with is in the server log (the published status
 * carries only the reason), so that a missing repository (404) is told from a rate limit (403) without a trace of the request: no address and no header in the line.
 */
class GithubReleaseSourceStatusLogTest {
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void capture() {
        logger = (Logger) LoggerFactory.getLogger(GithubReleaseSource.class);
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
        logged.stop();
    }

    private static GithubReleaseSource sourceAsking(String url) {
        SennardProperties properties = new Binder(new MapConfigurationPropertySource(Map.of("sennard.updates.tags-api", url))).bindOrCreate("sennard", SennardProperties.class);
        McpServerProperties server = new McpServerProperties();
        server.setVersion("6.0.0");
        return new GithubReleaseSource(JsonMapper.builder().build(), server, properties);
    }

    @Test
    void anHttpErrorIsRecordedAsHttpAndTheLogNamesTheStatusAndNotTheRequest() throws Exception {
        HttpServer repository = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        repository.createContext("/tags", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        repository.start();
        try {
            String url = "http://127.0.0.1:" + repository.getAddress().getPort() + "/tags";
            ReleaseSource.Answer answer = sourceAsking(url).ask();

            assertEquals(new ReleaseSource.Failed(PluginUpdateService.Reason.HTTP), answer);
            assertEquals(1, logged.list.size());
            ILoggingEvent line = logged.list.getFirst();
            assertEquals(Level.WARN, line.getLevel());
            assertTrue(line.getFormattedMessage().contains("httpStatus=404"), line.getFormattedMessage());
            assertTrue(line.getFormattedMessage().contains("reason=HTTP"), line.getFormattedMessage());
            assertFalse(line.getFormattedMessage().contains("127.0.0.1"), "the line does not name the request: " + line.getFormattedMessage());
        } finally {
            repository.stop(0);
        }
    }
}

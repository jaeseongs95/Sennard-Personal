// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.tray;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.awt.AWTError;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.context.ShutdownEndpoint;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.mock.env.MockEnvironment;

import io.github.jaeseongs95.sennard.app.message.CatalogMessageSource;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.server.web.AdminPageAddress;
import tools.jackson.databind.json.JsonMapper;

/**
 * The server's tray on a JVM that cannot show it, and the wording it shows when it can.
 *
 * <pre>
 * c1  headless      a headless JVM: nothing is handed to AWT, and the admin page's address, which is not known before the web server is ready, is not asked for
 * c2  no tray       a desktop without a system tray: the same
 * c3  close         closing the context of a server that showed nothing does not touch AWT
 * c4  wording       the tooltip and the menu entries are in the catalog, in English and in Korean
 * </pre>
 */
class ServerTrayTest {
    private static final Messages MESSAGES = new Messages(new MessageSourceAccessor(new CatalogMessageSource(new PathMatchingResourcePatternResolver(), JsonMapper.builder().build())));

    private static final AdminPageOpener NO_DESKTOP = new AdminPageOpener() {
        @Override
        public boolean supported() {
            return false;
        }

        @Override
        public void open(URI address) throws IOException {
            throw new IOException("no desktop");
        }
    };

    private static ServerTray tray(TrayAvailability availability) {
        // The address is never asked for: AdminPageAddress#origin() refuses before the web server has bound the admin page's connector.
        return new ServerTray(new AdminPageAddress(), new ShutdownEndpoint(), MESSAGES, new MockEnvironment(), () -> availability, NO_DESKTOP);
    }

    @Test
    void aHeadlessJvmShowsNothing() {
        ServerTray tray = tray(TrayAvailability.HEADLESS);
        assertDoesNotThrow(tray::show);
        assertFalse(tray.shown());
    }

    @Test
    void aDesktopWithoutASystemTrayShowsNothing() {
        ServerTray tray = tray(TrayAvailability.NO_SYSTEM_TRAY);
        assertDoesNotThrow(tray::show);
        assertFalse(tray.shown());
    }

    @Test
    void anUnreachableDisplayDoesNotAbortServerStartup() {
        ServerTray tray = new ServerTray(new AdminPageAddress(), new ShutdownEndpoint(), MESSAGES, new MockEnvironment(), () -> {
            throw new AWTError("Can't connect to X11 window server");
        }, NO_DESKTOP);
        assertDoesNotThrow(tray::show);
        assertFalse(tray.shown());
    }

    @Test
    void theSettingFalseShowsNothingEvenWhereATrayCanBeShown() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(ServerTray.ENABLED_SETTING, "false")));
        ServerTray tray = new ServerTray(new AdminPageAddress(), new ShutdownEndpoint(), MESSAGES, environment, () -> TrayAvailability.AVAILABLE, NO_DESKTOP);
        assertDoesNotThrow(tray::show);
        assertFalse(tray.shown());
    }

    @Test
    void closingTheContextOfAServerThatShowedNothingDoesNothing() {
        ServerTray tray = tray(TrayAvailability.HEADLESS);
        tray.show();
        assertDoesNotThrow(tray::hide);
        assertFalse(tray.shown());
    }

    @Test
    void theTooltipAndTheMenuEntriesAreInTheCatalogInEnglishAndKorean() {
        MessageRef tooltip = MessageRef.of("server.tray.tooltip", "version", "6.0.0", "address", "http://localhost:52531");
        assertAll(
                () -> assertEquals("Sennard 6.0.0 · Admin page http://localhost:52531", MESSAGES.render(tooltip, Locale.ENGLISH)),
                () -> assertEquals("Sennard 6.0.0 · 관리 페이지 http://localhost:52531", MESSAGES.render(tooltip, Locale.KOREAN)),
                () -> assertEquals("Open admin page", MESSAGES.render(MessageRef.of("server.tray.openAdminPage"), Locale.ENGLISH)),
                () -> assertEquals("관리 페이지 열기", MESSAGES.render(MessageRef.of("server.tray.openAdminPage"), Locale.KOREAN)),
                () -> assertEquals("Stop server", MESSAGES.render(MessageRef.of("server.tray.stopServer"), Locale.ENGLISH)),
                () -> assertEquals("서버 끄기", MESSAGES.render(MessageRef.of("server.tray.stopServer"), Locale.KOREAN)));
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.tray;

import java.awt.AWTError;
import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.util.Locale;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.context.ShutdownEndpoint;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.server.web.AdminPageAddress;

/**
 * The server's icon in the desktop's system tray: where the user finds the admin page, whose port is the operating system's choice at each start (so no bookmark can name it), and
 * how the server is stopped. Its menu opens the admin page and stops the server, and its tooltip says the version and the address; a double click opens the page.
 *
 * <p>It is shown once the server is ready ({@link ApplicationReadyEvent}: the admin page's connector is bound by then) and only when the JDK says a tray can be shown
 * ({@link TrayAvailability}); otherwise the server runs as it does without it and the log says why. The icon is removed when the context closes: a tray icon is a native resource the
 * JVM waits for, so one left behind would keep the process alive after the server stopped. AWT work is done on the event dispatch thread, in the order it was asked.
 *
 * <p>The server's own shutdown is the one {@code sennard stop} reaches over HTTP: Boot's {@link ShutdownEndpoint}, called here without the request. The wording is in the catalog and is
 * the language of the JVM's default locale: nobody's request is being answered.
 */
@Component
final class ServerTray {
    private static final Logger LOG = LoggerFactory.getLogger(ServerTray.class);
    /**
     * {@code sennard.tray.enabled} (environment {@code SENNARD_TRAY_ENABLED}), true unless stated: a user who wants no icon, and the build's test runs, say false. A JVM does not hand a system
     * property such as {@code java.awt.headless} to the child JVMs it starts, and a server a test starts is a child of the test (or of the installer's command), so the one thing every child
     * inherits, the environment, carries the choice; {@code JAVA_TOOL_OPTIONS} would do it too but makes every JVM print a notice on standard error, which tests of that output refuse.
     */
    static final String ENABLED_SETTING = "sennard.tray.enabled";

    private final AdminPageAddress adminPage;
    private final ShutdownEndpoint shutdown;
    private final Messages messages;
    private final Environment environment;
    private final Supplier<TrayAvailability> availability;
    private final AdminPageOpener opener;
    private volatile boolean scheduled; // true once the icon was handed to the event dispatch thread
    private volatile TrayIcon icon; // on the event dispatch thread: set after the icon is in the tray, null after it is removed

    @Autowired
    ServerTray(AdminPageAddress adminPage, ShutdownEndpoint shutdown, Messages messages, Environment environment) {
        this(adminPage, shutdown, messages, environment, TrayAvailability::current, new DesktopAdminPageOpener());
    }

    ServerTray(AdminPageAddress adminPage, ShutdownEndpoint shutdown, Messages messages, Environment environment, Supplier<TrayAvailability> availability, AdminPageOpener opener) {
        this.adminPage = adminPage;
        this.shutdown = shutdown;
        this.messages = messages;
        this.environment = environment;
        this.availability = availability;
        this.opener = opener;
    }

    /** Whether the icon was handed to AWT: false on a headless JVM and on a desktop without a tray. */
    boolean shown() {
        return scheduled;
    }

    @EventListener(ApplicationReadyEvent.class)
    void show() {
        boolean enabled = environment.getProperty(ENABLED_SETTING, Boolean.class, true);
        TrayAvailability now;
        try {
            now = enabled ? availability.get() : TrayAvailability.DISABLED;
        } catch (AWTError failed) {
            LOG.warn("Could not check system tray availability. The server runs without a tray icon.", failed);
            return;
        }
        if (now != TrayAvailability.AVAILABLE) {
            LOG.info("No tray icon: {}.", now.reason());
            return;
        }
        String address = adminPage.origin();
        TrayActions actions = new TrayActions(opener, () -> shutdown.shutdown(), address);
        Locale locale = Locale.getDefault();
        String tooltip = messages.render(MessageRef.of("server.tray.tooltip", "version", environment.getRequiredProperty("spring.ai.mcp.server.version"), "address", address), locale);
        String openLabel = messages.render(MessageRef.of("server.tray.openAdminPage"), locale);
        String stopLabel = messages.render(MessageRef.of("server.tray.stopServer"), locale);
        scheduled = true;
        EventQueue.invokeLater(() -> add(actions, tooltip, openLabel, stopLabel));
    }

    /** On the event dispatch thread. */
    private void add(TrayActions actions, String tooltip, String openLabel, String stopLabel) {
        try {
            SystemTray tray = SystemTray.getSystemTray();
            Dimension size = tray.getTrayIconSize();
            MenuItem open = new MenuItem(openLabel);
            open.setEnabled(actions.canOpenAdminPage());
            open.addActionListener(event -> actions.openAdminPage());
            MenuItem stop = new MenuItem(stopLabel);
            stop.addActionListener(event -> actions.stopServer());
            PopupMenu menu = new PopupMenu();
            menu.add(open);
            menu.addSeparator();
            menu.add(stop);
            TrayIcon created = new TrayIcon(TrayImage.draw(Math.min(size.width, size.height)), tooltip, menu);
            created.setImageAutoSize(true);
            created.addActionListener(event -> actions.openAdminPage());
            tray.add(created);
            icon = created;
        } catch (AWTException | RuntimeException failed) {
            // Boundary (AWT): the icon is the server's convenience, so a desktop that refuses it is a line in the log and the server goes on.
            LOG.warn("The tray icon could not be shown; the server runs without it.", failed);
        }
    }

    @EventListener(ContextClosedEvent.class)
    void hide() {
        if (!scheduled) {
            return;
        }
        scheduled = false;
        EventQueue.invokeLater(() -> {
            TrayIcon shownIcon = icon;
            if (shownIcon != null) {
                SystemTray.getSystemTray().remove(shownIcon);
                icon = null;
            }
        });
    }
}

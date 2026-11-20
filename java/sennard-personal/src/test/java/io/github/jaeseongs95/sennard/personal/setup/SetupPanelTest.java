// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.personal.install.Installer.Status;
import io.github.jaeseongs95.sennard.personal.setup.SetupService.Action;
import io.github.jaeseongs95.sennard.personal.setup.SetupService.Request;
import io.github.jaeseongs95.sennard.personal.setup.SetupService.Result;

class SetupPanelTest {
    private static final String RELEASE = "1.10.0";
    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(SetupWindow.class.getPackageName() + ".Messages", Locale.ROOT);

    @Test
    void freshCurrentAndNewerInstallationsShowTheAvailableAction() throws Exception {
        record Scenario(Status status, String heading, String button) {
        }
        for (Scenario scenario : List.of(
                new Scenario(status(List.of(), null), "No installation", "Install"),
                new Scenario(status(List.of(RELEASE), null), "This package matches the installed version.", "Start server"),
                new Scenario(status(List.of("1.11.0"), 8080), "The installed version is newer", null))) {
            SetupPanel panel = onEdt(() -> new SetupPanel(MESSAGES, RELEASE, request -> {
                assertEquals(Action.REFRESH, request.action());
                return new Result(scenario.status(), false, null, null, "");
            }));
            onEdt(() -> attachAndLoad(panel));
            awaitStatus(panel, scenario.heading());
            onEdt(() -> {
                JButton action = primary(panel);
                assertEquals(scenario.button() != null, action.isVisible());
                assertEquals(scenario.button(), action.isVisible() ? action.getText() : null);
                return null;
            });
        }
    }

    @Test
    void bodyTracksTheNarrowViewportEvenWhenItsPreferredWidthIsLarger() throws Exception {
        onEdt(() -> {
            SetupPanel panel = new SetupPanel(MESSAGES, RELEASE, request -> {
                throw new AssertionError("Resizing must not run installer actions");
            });
            JScrollPane scroll = components(panel.content()).filter(JScrollPane.class::isInstance).map(JScrollPane.class::cast)
                    .filter(pane -> pane.getParent() == panel.content()).findFirst().orElseThrow();
            JViewport viewport = scroll.getViewport();
            JLabel subtitle = components(panel.content()).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                    .filter(label -> label.getText().equals(MESSAGES.getString("heading"))).findFirst().orElseThrow();
            Container headings = subtitle.getParent();
            Container header = headings.getParent();
            viewport.getView().setPreferredSize(new Dimension(1000, 600));
            for (Dimension size : List.of(new Dimension(514, 605), new Dimension(510, 390))) {
                panel.content().setSize(size);
                panel.content().doLayout();
                header.doLayout();
                headings.doLayout();
                scroll.doLayout();
                viewport.doLayout();
                assertEquals(subtitle.getPreferredSize().width, subtitle.getWidth());
                assertTrue(scroll.getVerticalScrollBar().isVisible());
                assertEquals(viewport.getExtentSize().width, viewport.getView().getWidth());
            }
            return null;
        });
    }

    @Test
    void upgradeRequiresConsentRunsOnceAndReportsPartialFailureWithCurrentStatus() throws Exception {
        CountDownLatch finishUpdate = new CountDownLatch(1);
        CompletableFuture<Request> started = new CompletableFuture<>();
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean closed = new AtomicBoolean();
        Status old = status(List.of("1.9.0"), 8080);
        Status current = status(List.of(RELEASE, "1.9.0"), 8081);
        SetupPanel panel = onEdt(() -> new SetupPanel(MESSAGES, RELEASE, request -> {
            assertFalse(SwingUtilities.isEventDispatchThread());
            if (request.action() == Action.REFRESH) {
                return new Result(old, false, null, null, "");
            }
            requests.incrementAndGet();
            started.complete(request);
            try {
                assertTrue(finishUpdate.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            return new Result(current, false, "HOST_FILES_UNAVAILABLE", null, "Server started");
        }));
        JRootPane root = onEdt(() -> {
            panel.onClose(() -> closed.set(true));
            return attachAndLoad(panel);
        });
        awaitStatus(panel, "Updating restarts the local server.");
        try {
            onEdt(() -> {
                JButton update = primary(panel);
                assertEquals("Update and restart", update.getText());
                assertFalse(update.isEnabled());
                assertNull(root.getDefaultButton());
                update.doClick(0);
                assertFalse(started.isDone());
                JCheckBox consent = components(panel.content()).filter(JCheckBox.class::isInstance).map(JCheckBox.class::cast)
                        .filter(check -> check.getMnemonic() == KeyEvent.VK_B).findFirst().orElseThrow();
                assertTrue(consent.isVisible());
                consent.doClick(0);
                assertEquals(update, root.getDefaultButton());
                update.doClick(0);
                assertFalse(update.isEnabled());
                assertFalse(consent.isEnabled());
                assertNull(root.getDefaultButton());
                update.doClick(0);
                panel.requestClose();
                assertFalse(closed.get());
                assertTrue(hasVisibleText(panel, "Updating and restarting the server"));
                return null;
            });
            Request observed = started.get(5, TimeUnit.SECONDS);
            assertEquals(Action.UPGRADE, observed.action());
            assertEquals(old, observed.displayed());
        } finally {
            finishUpdate.countDown();
        }
        awaitStatus(panel, "The update stopped before all steps finished");
        onEdt(() -> {
            assertTrue(hasVisibleText(panel, "Installed version: " + RELEASE));
            assertTrue(hasVisibleText(panel, "HOST_FILES_UNAVAILABLE"));
            assertFalse(primary(panel).isVisible());
            panel.requestClose();
            assertTrue(closed.get());
            return null;
        });
        assertEquals(1, requests.get());
    }

    private static JRootPane attachAndLoad(SetupPanel panel) {
        JRootPane root = new JRootPane();
        root.setContentPane(panel.content());
        panel.load();
        return root;
    }

    private static Status status(List<String> installed, Integer port) {
        return new Status("test-installation", null, installed, null, port, port != null, null, null, null);
    }

    private static void awaitStatus(SetupPanel panel, String snippet) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (onEdt(() -> hasVisibleText(panel, snippet))) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Setup did not show: " + snippet);
    }

    private static boolean hasVisibleText(SetupPanel panel, String snippet) {
        return components(panel.content()).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                .anyMatch(area -> area.isVisible() && area.getText().contains(snippet));
    }

    private static JButton primary(SetupPanel panel) {
        return components(panel.content()).filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(button -> button.getMnemonic() == KeyEvent.VK_S).findFirst().orElseThrow();
    }

    private static Stream<Component> components(Container parent) {
        return Stream.of(parent.getComponents()).flatMap(component -> component instanceof Container container
                ? Stream.concat(Stream.of(component), components(container)) : Stream.of(component));
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> work = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(work);
        return work.get();
    }
}

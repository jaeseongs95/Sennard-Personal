// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.setup;

import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.WindowConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.personal.install.InstallEnvironment;
import io.github.jaeseongs95.sennard.personal.install.Installer;
import io.github.jaeseongs95.sennard.personal.install.RunningProgram;

/** The CLI process lives until its setup window closes. All window creation and updates run on Swing's event thread. */
public final class SetupWindow {
    private static final Logger LOG = LoggerFactory.getLogger(SetupWindow.class);

    private SetupWindow() {
    }

    public static void showAndWait(InstallEnvironment environment, RunningProgram running, String version, Function<SennardException, String> describeFailure) {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try {
                useSystemLookAndFeel();
                var messages = ResourceBundle.getBundle(SetupWindow.class.getPackageName() + ".Messages");
                var installer = new SetupService(out -> new Installer(environment, running, version, out), describeFailure);
                var panel = new SetupPanel(messages, version, installer::run);
                JFrame frame = new JFrame(messages.getString("title"));
                frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                frame.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent event) {
                        panel.requestClose();
                    }

                    @Override
                    public void windowClosed(WindowEvent event) {
                        closed.complete(null);
                    }
                });
                frame.setContentPane(panel.content());
                panel.onClose(frame::dispose);
                frame.setPreferredSize(new Dimension(530, 644));
                frame.pack();
                frame.setMinimumSize(new Dimension(510, 390));
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
                panel.load();
            } catch (RuntimeException failure) {
                closed.completeExceptionally(failure);
            }
        });
        closed.join();
    }

    private static void useSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ReflectiveOperationException | UnsupportedLookAndFeelException unavailable) {
            // Swing's default look and feel remains available if the platform's look and feel cannot load.
            LOG.warn("Setup could not load the system look and feel", unavailable);
        }
    }
}

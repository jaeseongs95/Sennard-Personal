// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.setup;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.Serial;
import java.net.URI;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicPanelUI;
import javax.swing.plaf.basic.BasicProgressBarUI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.jaeseongs95.sennard.personal.install.Installer.Status;
import io.github.jaeseongs95.sennard.personal.setup.SetupService.Action;
import io.github.jaeseongs95.sennard.personal.setup.SetupService.Mode;
import io.github.jaeseongs95.sennard.personal.setup.SetupService.Request;
import io.github.jaeseongs95.sennard.personal.setup.SetupService.Result;

/** Presents the observed installation and runs only the action the user selected. */
final class SetupPanel {
    private static final Logger LOG = LoggerFactory.getLogger(SetupPanel.class);
    private static final String PROJECT = "https://github.com/jaeseongs95/Sennard-Personal";
    private static final Color CANVAS = new Color(0xF0F4F7);
    private static final Color INK = new Color(0x142639);
    private static final Color SECONDARY = new Color(0x465B70);
    private static final Color BORDER = new Color(0xC9D6E2);
    private static final Color PRIMARY = new Color(0x106756);
    private static final Color FOCUS = new Color(0x007967);
    private final JPanel content = new JPanel(new BorderLayout());
    private final ResourceBundle messages;
    private final String version;
    private final Function<Request, Result> execute;

    private final JTextArea summary = textArea(0);
    private final JTextArea packageVersion = textArea(0);
    private final JTextArea installationDetails = textArea(0);
    private final JTextArea guidance = textArea(0);
    private final JTextArea feedback = textArea(0);
    private final JTextArea hostStatus = textArea(0);
    private final JTextArea details = textArea(6);
    private final JProgressBar progress = new JProgressBar();
    private final JScrollPane detailScroll = new JScrollPane(details);
    private final JButton primary = new JButton();
    private final JButton refresh;
    private final JButton hooks;
    private final JButton admin;
    private final JButton releases;
    private final JButton hostGuide;
    private final JButton close;
    private final JCheckBox confirmed;
    private final JCheckBox showDetails;

    private Status status;
    private boolean working;
    private Runnable dispose;

    SetupPanel(ResourceBundle messages, String version, Function<Request, Result> execute) {
        this.messages = messages;
        this.version = version;
        this.execute = execute;

        releases = button("releases", KeyEvent.VK_R, () -> browse(PROJECT + "/releases"));
        refresh = button("refresh", KeyEvent.VK_F, this::load);
        close = button("close", KeyEvent.VK_C, this::requestClose);
        admin = button("admin", KeyEvent.VK_A, () -> browse(status.adminPage()));
        hooks = button("hooks", KeyEvent.VK_H, () -> run(Action.HOOKS));
        String readme = Locale.KOREAN.getLanguage().equals(messages.getLocale().getLanguage()) ? "README.ko.md" : "README.md";
        hostGuide = button("hostGuide", KeyEvent.VK_G, () -> browse(PROJECT + "/blob/main/" + readme + "#" + message("guideAnchor")));

        primary.setMnemonic(KeyEvent.VK_S);
        styleButton(primary, true);
        primary.addActionListener(event -> run(primaryAction()));
        confirmed = checkBox("confirmed");
        confirmed.setMnemonic(KeyEvent.VK_B);
        confirmed.addActionListener(event -> updateControls());
        showDetails = checkBox("details");
        showDetails.setMnemonic(KeyEvent.VK_D);
        showDetails.addActionListener(event -> detailScroll.setVisible(showDetails.isSelected()));
        showDetails.setVisible(false);

        summary.setFont(font(Font.BOLD, 14));
        packageVersion.setFont(font(Font.BOLD, 14));
        installationDetails.setForeground(SECONDARY);
        hostStatus.setForeground(SECONDARY);
        feedback.setForeground(PRIMARY);
        feedback.setFont(font(Font.BOLD, 13));
        name(summary, "installation");
        name(packageVersion, "installation");
        name(installationDetails, "installation");
        name(guidance, "nextStep");
        name(feedback, "outcome");
        name(hostStatus, "hostStatus");
        name(details, "details");
        progress.getAccessibleContext().setAccessibleName(message("outcome"));
        progress.setUI(new BasicProgressBarUI());
        progress.setForeground(PRIMARY);
        progress.setBackground(new Color(0xE6F0EE));
        progress.setBorderPainted(false);
        progress.setMinimumSize(new Dimension(0, 6));
        progress.setPreferredSize(new Dimension(128, 6));
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        progress.setIndeterminate(false);
        progress.setVisible(false);

        JPanel installation = card();
        JPanel versionRow = new JPanel(new GridLayout(1, 2, 12, 0));
        versionRow.setOpaque(false);
        versionRow.add(summary);
        versionRow.add(packageVersion);
        addRow(installation, versionRow);
        installation.add(Box.createVerticalStrut(5));
        addRow(installation, installationDetails);
        installation.add(Box.createVerticalStrut(10));
        addRow(installation, guidance);
        addRow(installation, confirmed);
        addRow(installation, buttons(primary, admin));
        addRow(installation, feedback);
        addRow(installation, progress);
        addRow(installation, showDetails);
        addRow(installation, detailScroll);
        detailScroll.setVisible(false);

        JPanel hosts = card();
        addRow(hosts, section("hosts"));
        hosts.add(Box.createVerticalStrut(5));
        addRow(hosts, hostStatus);
        addRow(hosts, buttons(hostGuide, hooks));

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.PAGE_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(16, 24, 4, 24));
        addRow(body, installation);
        body.add(Box.createVerticalStrut(12));
        addRow(body, hosts);
        JPanel scrollBody = new ViewportPanel();
        scrollBody.setBackground(CANVAS);
        scrollBody.add(body, BorderLayout.PAGE_START);
        JScrollPane scroll = new JScrollPane(scrollBody);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(CANVAS);
        scroll.setPreferredSize(new Dimension(680, 390));
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        content.add(header(), BorderLayout.PAGE_START);
        content.add(scroll, BorderLayout.CENTER);
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 8));
        footer.setBackground(CANVAS);
        footer.setBorder(BorderFactory.createEmptyBorder(0, 16, 7, 16));
        footer.add(releases);
        footer.add(refresh);
        footer.add(close);
        content.add(footer, BorderLayout.PAGE_END);
        showStatus();
        updateControls();
    }

    JPanel content() {
        return content;
    }

    void onClose(Runnable dispose) {
        this.dispose = dispose;
    }

    void requestClose() {
        if (!working) {
            dispose.run();
        }
    }

    void load() {
        run(Action.REFRESH);
    }

    private void run(Action action) {
        if (working || action == null || (action == Action.UPGRADE && !confirmed.isSelected())) {
            return;
        }
        Request request = new Request(action, status);
        working = true;
        updateControls();
        feedback.setForeground(PRIMARY);
        set(feedback, message("working." + action.name()));
        new SwingWorker<Result, Void>() {
            @Override
            protected Result doInBackground() {
                return execute.apply(request);
            }

            @Override
            protected void done() {
                try {
                    showResult(action, get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    showResult(action, new Result(null, false, null, interrupted.toString(), ""));
                } catch (ExecutionException failed) {
                    LOG.warn("Setup action failed", failed.getCause());
                    showResult(action, new Result(null, false, null, failed.getCause().toString(), ""));
                }
            }
        }.execute();
    }

    private void showResult(Action action, Result result) {
        working = false;
        status = result.status();
        confirmed.setSelected(false);
        showStatus();
        String log = result.details();
        String outcome = "";
        if (result.changed()) {
            outcome = message("changed");
        } else if (result.failure() != null) {
            outcome = message("failure." + action.name());
            log += "\n" + result.failure();
        } else if (result.statusFailure() == null && action != Action.REFRESH) {
            outcome = message("success." + action.name());
        }
        if (result.statusFailure() != null) {
            outcome += (outcome.isEmpty() ? "" : "\n") + message("statusFailure");
            log += "\n" + result.statusFailure();
        }
        boolean failure = result.failure() != null || result.statusFailure() != null;
        feedback.setForeground(failure ? new Color(0x8C352B) : PRIMARY);
        set(feedback, outcome);
        set(details, log.strip());
        showDetails.setVisible(!log.isBlank());
        showDetails.setSelected(failure);
        detailScroll.setVisible(failure);
        updateControls();
        if (!outcome.isEmpty()) {
            feedback.requestFocusInWindow();
        } else if (confirmed.isVisible()) {
            confirmed.requestFocusInWindow();
        } else if (primary.isEnabled() && primary.isVisible()) {
            primary.requestFocusInWindow();
        } else {
            refresh.requestFocusInWindow();
        }
    }

    private void showStatus() {
        set(packageVersion, message("packageVersion", version));
        if (status == null) {
            set(summary, "");
            set(installationDetails, "");
            set(guidance, "");
            set(hostStatus, message("hostGuideOnly"));
            return;
        }
        Mode mode = SetupService.mode(status, version);
        String installedVersion = status.versions().isEmpty() ? message("missing") : status.versions().getFirst();
        set(summary, message("installedVersion", installedVersion));
        StringBuilder text = new StringBuilder(message("location", status.installRoot()));
        if (mode != Mode.INSTALL) {
            String server = Boolean.TRUE.equals(status.healthy()) ? "healthy" : status.port() == null ? "stopped" : "unavailable";
            String database = status.store() == null ? "unknown" : status.store().phase().name();
            text.append('\n').append(message("server", message(server))).append('\n').append(message("store", message("store." + database)));
            if (status.store() != null && status.store().reason() != null) {
                text.append('\n').append(status.store().reason());
            }
        }
        if (status.adminPage() != null) {
            text.append('\n').append(message("adminAddress", status.adminPage()));
        }
        set(installationDetails, text.toString());
        set(guidance, message("mode." + mode.name()));
        String hostText = message("hostGuideOnly");
        if (status.hostCopiesWithoutDefinitions() != null && !status.hostCopiesWithoutDefinitions().isEmpty()) {
            hostText += "\n" + message("missingHooks") + "\n" + String.join("\n", status.hostCopiesWithoutDefinitions());
        }
        set(hostStatus, hostText);
    }

    private Action primaryAction() {
        if (status == null) {
            return null;
        }
        return switch (SetupService.mode(status, version)) {
            case INSTALL -> Action.INSTALL;
            case UPGRADE -> Action.UPGRADE;
            case CURRENT, NEWER -> status.port() == null ? Action.START : null;
        };
    }

    private void updateControls() {
        Action action = primaryAction();
        confirmed.setVisible(action == Action.UPGRADE);
        primary.setVisible(action != null);
        if (action != null) {
            primary.setText(message("action." + action.name()));
        }
        confirmed.setEnabled(!working);
        primary.setEnabled(!working && action != null && (action != Action.UPGRADE || confirmed.isSelected()));
        hooks.setEnabled(!working && status != null && !status.versions().isEmpty());
        hooks.setVisible(status != null && !status.versions().isEmpty());
        admin.setEnabled(!working && status != null && status.adminPage() != null);
        admin.setVisible(status != null && status.adminPage() != null);
        releases.setEnabled(!working);
        hostGuide.setEnabled(!working);
        refresh.setEnabled(!working);
        close.setEnabled(!working);
        progress.setIndeterminate(working);
        progress.setVisible(working);
        JRootPane root = SwingUtilities.getRootPane(content);
        if (root != null) {
            root.setDefaultButton(primary.isEnabled() ? primary : null);
        }
    }

    private void browse(String url) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            set(feedback, message("browserFailure", url));
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (IOException | IllegalArgumentException | SecurityException unavailable) {
            LOG.warn("Setup could not open the browser", unavailable);
            set(feedback, message("browserFailure", url));
        }
    }

    private String message(String key, Object... values) {
        return values.length == 0 ? messages.getString(key) : new MessageFormat(messages.getString(key), messages.getLocale()).format(values);
    }

    private JButton button(String key, int mnemonic, Runnable action) {
        JButton button = new JButton(message(key));
        styleButton(button, false);
        button.setMnemonic(mnemonic);
        button.addActionListener(event -> action.run());
        return button;
    }

    private JLabel section(String key) {
        JLabel label = new JLabel(message(key));
        label.setFont(font(Font.BOLD, 14));
        label.setForeground(INK);
        return label;
    }

    private JCheckBox checkBox(String key) {
        JCheckBox check = new JCheckBox(message(key));
        check.setOpaque(false);
        check.setForeground(INK);
        check.setFont(font(Font.PLAIN, 13));
        return check;
    }

    private JPanel header() {
        JPanel header = new JPanel(new BorderLayout(14, 0));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(20, 26, 20, 26));
        header.setUI(new BasicPanelUI() {
            @Override
            public void paint(Graphics original, JComponent component) {
                Graphics2D graphics = (Graphics2D) original.create();
                graphics.setPaint(new GradientPaint(0, 0, new Color(0x102239), component.getWidth(), component.getHeight(), new Color(0x174D52)));
                graphics.fillRect(0, 0, component.getWidth(), component.getHeight());
                graphics.dispose();
            }
        });
        JLabel heading = new JLabel("Sennard Personal");
        heading.setForeground(Color.WHITE);
        heading.setFont(font(Font.BOLD, 24));
        JLabel subtitle = new JLabel(message("heading"));
        subtitle.setForeground(new Color(0xD5EDE9));
        subtitle.setFont(font(Font.PLAIN, 13));
        Box headings = Box.createVerticalBox();
        headings.add(heading);
        headings.add(subtitle);
        JPanel mark = new JPanel();
        mark.setOpaque(false);
        mark.setPreferredSize(new Dimension(38, 38));
        mark.setUI(new BasicPanelUI() {
            @Override
            public void paint(Graphics original, JComponent component) {
                Graphics2D graphics = smooth(original);
                graphics.translate(0, (component.getHeight() - 38) / 2);
                graphics.setColor(new Color(0xC9F4E7));
                graphics.fillRoundRect(0, 0, 38, 38, 12, 12);
                graphics.setColor(new Color(0x18584E));
                graphics.fillRoundRect(11, 10, 16, 4, 4, 4);
                graphics.fillRoundRect(11, 17, 16, 4, 4, 4);
                graphics.fillRoundRect(11, 24, 10, 4, 4, 4);
                graphics.dispose();
            }
        });
        header.add(mark, BorderLayout.LINE_START);
        header.add(headings, BorderLayout.CENTER);
        return header;
    }

    private static JPanel card() {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.PAGE_AXIS));
        card.setOpaque(false);
        card.setBorder(BorderFactory.createEmptyBorder(15, 18, 7, 18));
        card.setUI(new BasicPanelUI() {
            @Override
            public void paint(Graphics original, JComponent component) {
                Graphics2D graphics = smooth(original);
                graphics.setColor(Color.WHITE);
                graphics.fillRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, 17, 17);
                graphics.setColor(BORDER);
                graphics.drawRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, 17, 17);
                graphics.dispose();
            }
        });
        return card;
    }

    private static void styleButton(JButton button, boolean primary) {
        button.setUI(new BasicButtonUI() {
            @Override
            public void paint(Graphics original, JComponent component) {
                Graphics2D graphics = smooth(original);
                int width = component.getWidth() - 3;
                int height = component.getHeight() - 3;
                Color surface = Color.WHITE;
                if (primary && button.isEnabled()) {
                    surface = button.getModel().isPressed() ? new Color(0x0B4C40) : PRIMARY;
                } else if (!button.isEnabled()) {
                    surface = new Color(0xE8EEF2);
                } else if (button.getModel().isRollover() || button.getModel().isPressed()) {
                    surface = new Color(0xE6F4EF);
                }
                graphics.setColor(surface);
                graphics.fillRoundRect(1, 1, width, height, 10, 10);
                if (!primary || button.hasFocus()) {
                    graphics.setColor(button.hasFocus() ? FOCUS : BORDER);
                    graphics.drawRoundRect(1, 1, width, height, 10, 10);
                }
                if (button.hasFocus()) {
                    graphics.setColor(primary && button.isEnabled() ? Color.WHITE : FOCUS);
                    graphics.drawRoundRect(3, 3, width - 4, height - 4, 7, 7);
                }
                graphics.dispose();
                super.paint(original, component);
            }
        });
        button.setFont(font(Font.BOLD, 13));
        button.setForeground(primary ? Color.WHITE : INK);
        button.setBorder(BorderFactory.createEmptyBorder(8, 13, 8, 13));
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setRolloverEnabled(true);
        button.setFocusPainted(false);
    }

    private static Font font(int style, float size) {
        return new JLabel().getFont().deriveFont(style, size);
    }

    private static Graphics2D smooth(Graphics original) {
        Graphics2D graphics = (Graphics2D) original.create();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return graphics;
    }

    private void name(JTextArea area, String key) {
        area.getAccessibleContext().setAccessibleName(message(key));
    }

    private static JPanel buttons(JButton... buttons) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEADING, 7, 6));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(0, -7, 0, 0));
        for (JButton button : buttons) {
            panel.add(button);
        }
        return panel;
    }

    private static void addRow(JPanel panel, JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(component);
    }

    private static JTextArea textArea(int rows) {
        JTextArea area = new JTextArea(rows, 1);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
        area.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
        area.setFont(font(Font.PLAIN, 13));
        area.setForeground(INK);
        area.setMargin(new Insets(0, 0, 0, 0));
        if (rows == 0) {
            area.setOpaque(false);
        }
        return area;
    }

    private static void set(JTextArea area, String text) {
        area.setText(text);
        area.setCaretPosition(0);
        area.setVisible(!text.isEmpty());
    }

    private static final class ViewportPanel extends JPanel implements Scrollable {
        @Serial
        private static final long serialVersionUID = 1L;
        private static final int SCROLL_STEP = 20;

        private ViewportPanel() {
            super(new BorderLayout());
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return SCROLL_STEP;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(SCROLL_STEP, visible.height - SCROLL_STEP);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.tray;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** The tray icon, drawn: a filled circle with the letter S. The release is one jar and holds no binary image file, and Java2D draws it at the size the tray asks for. */
final class TrayImage {
    private static final Color BACKGROUND = new Color(0x2F6FEB);
    private static final Color LETTER = Color.WHITE;

    private TrayImage() {
    }

    /** @param size the side of the square image in pixels ({@code SystemTray.getTrayIconSize()}) */
    static BufferedImage draw(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(BACKGROUND);
            graphics.fillOval(0, 0, size, size);
            graphics.setColor(LETTER);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.round(size * 0.7f)));
            FontMetrics metrics = graphics.getFontMetrics();
            int x = (size - metrics.stringWidth("S")) / 2;
            int y = (size - metrics.getHeight()) / 2 + metrics.getAscent();
            graphics.drawString("S", x, y);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}

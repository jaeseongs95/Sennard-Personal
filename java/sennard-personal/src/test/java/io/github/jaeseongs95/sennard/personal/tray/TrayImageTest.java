// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.tray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/** The drawn icon is a circle with the letter S at the size the tray asks for: Java2D draws it without a window, so a headless JVM checks it. */
class TrayImageTest {
    @Test
    void theImageHasTheSizeAskedForWithATransparentCornerAnOpaqueCircleAndALetter() {
        int size = 32;
        BufferedImage image = TrayImage.draw(size);
        assertEquals(size, image.getWidth());
        assertEquals(size, image.getHeight());
        assertEquals(0, image.getRGB(0, 0) >>> 24, "the corner is outside the circle");
        assertEquals(0xFF2F6FEB, image.getRGB(size / 2, 2), "the top of the circle is its colour");
        int white = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (image.getRGB(x, y) == 0xFFFFFFFF) {
                    white++;
                }
            }
        }
        assertTrue(white > 0, "the letter is drawn");
    }
}

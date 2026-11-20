// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.protocol;

import java.nio.charset.Charset;

/** A process for {@link Utf8BoundaryTest}: {@code streams} prints through the fixed standard streams, {@code native} writes the system code page's bytes. */
public final class Utf8BoundaryProbe {
    static final String TEXT = "표준 한글";

    private Utf8BoundaryProbe() {
    }

    public static void main(String[] args) {
        if (args[0].equals("streams")) {
            Utf8Boundary.fixStandardStreams();
            System.out.print(TEXT);
            System.err.print(TEXT);
            System.out.flush();
            System.err.flush();
            return;
        }
        String text = new String(Character.toChars(Integer.parseInt(args[1])));
        byte[] bytes = text.getBytes(Charset.forName(System.getProperty("native.encoding")));
        System.out.write(bytes, 0, bytes.length);
        System.out.flush();
    }
}

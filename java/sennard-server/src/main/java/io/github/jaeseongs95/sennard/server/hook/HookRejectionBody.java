// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

/** The body of a refused hook call: {@code {"reason":"<code>"}}. */
public record HookRejectionBody(String reason) {
    public static HookRejectionBody of(HookRejected rejected) {
        return new HookRejectionBody(rejected.reason());
    }
}

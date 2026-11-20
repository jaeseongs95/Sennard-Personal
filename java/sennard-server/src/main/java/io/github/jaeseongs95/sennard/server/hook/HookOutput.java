// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

/**
 * What a host reads as a hook's output, as the record of that host's own wire format. Every adapter answers with a record of this type; the transport
 * writes it with the application's {@code JsonMapper}, so no adapter builds JSON by hand.
 */
public interface HookOutput {
}

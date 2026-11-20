// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

/**
 * A host adapter that provides both its plugin registration and its server-side hook translation.
 */
public interface HostHookAdapter extends ClientHookRegistration, ServerHookAdapter {
}

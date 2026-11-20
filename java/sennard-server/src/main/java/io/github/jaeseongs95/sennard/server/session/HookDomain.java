// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.session;

import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HostEventHandler.HookCall;

/**
 * A domain's part in answering a hook. The session side has already registered the session and left the admission row when this
 * is called. Beans of this type run in their {@code @Order}.
 */
public interface HookDomain {
    /**
     * @return {@link HookVerdict.Proceed} for nothing to add. A refusal or a continued turn ends the round and is the answer; contexts of
     *     several domains are joined
     */
    HookVerdict onEvent(HookCall call);
}

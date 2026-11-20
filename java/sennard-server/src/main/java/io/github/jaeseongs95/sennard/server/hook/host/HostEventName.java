// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.util.EnumSet;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A host's name for an event, as an adapter's {@code Event} enum holds it. The name a hook is registered under is turned into the enum at one place per
 * adapter ({@link #index}); everything after that is an exhaustive {@code switch}.
 */
interface HostEventName {
    /** The name the host gives the event: the last path segment of the hook's endpoint, and the host's own {@code hook_event_name}. */
    String hostName();

    static <E extends Enum<E> & HostEventName> Map<String, E> index(Class<E> type) {
        return EnumSet.allOf(type).stream().collect(Collectors.toUnmodifiableMap(HostEventName::hostName, Function.identity()));
    }
}

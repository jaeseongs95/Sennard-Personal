// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What the server's {@code /actuator/sennard} says about the admin page, read by {@code sennard status}. The page's port is the operating system's choice at each start of the
 * server, so the running server is the only place that knows it.
 *
 * @param adminPage the origin of the page, {@code http://localhost:<port>}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdminPageStatus(String adminPage) {
}

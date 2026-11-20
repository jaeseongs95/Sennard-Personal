// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import io.github.jaeseongs95.sennard.server.web.AdminPageAddress;
import io.github.jaeseongs95.sennard.server.web.FilterRefusals;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** The page's API routes ({@code /api/*}): a request that names the loopback address is refused, whatever its method, since there is no page here to move. */
final class AdminApiFilter extends AdminPortFilter {
    AdminApiFilter(AdminPageAddress address, FilterRefusals refusals) {
        super(address, refusals);
    }

    @Override
    void loopbackAddressed(HttpServletRequest request, HttpServletResponse response) {
        refuse(request, response, AdminRefusal.HOST_REJECTED);
    }
}

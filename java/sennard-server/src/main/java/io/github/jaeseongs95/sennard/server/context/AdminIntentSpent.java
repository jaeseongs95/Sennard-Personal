// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

/**
 * Published inside the commit of a signed record, for the intent that record was made from. {@link AdminIssuedIntents} spends the intent after the transaction commits, so a
 * transaction that rolls back or fails to commit does not use up the intent the page was shown.
 */
public record AdminIntentSpent(String intent) {
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

/**
 * What the server found when it compared the attachment an item of a stage result names with the item's declared digest: the attachment's sha256 is that digest
 * ({@code SHA256_MATCHED}), or it is not ({@code NOT_CHECKED}, recorded only for a stage that did not pass). Written in {@code artifactChecks[].check} by the constant's name.
 */
public enum ArtifactCheck {
    SHA256_MATCHED,
    NOT_CHECKED
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

/** Which list of a stage result an artifact check looked at: the output's {@code ARTIFACT}s or the result's {@code EVIDENCE}. Written in {@code artifactChecks[].source} by the constant's name. */
public enum CheckSource {
    ARTIFACT,
    EVIDENCE
}

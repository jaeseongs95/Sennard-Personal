// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.error;

/**
 * The seven kinds of failure Sennard tells apart. Each has one subclass of {@link SennardException}, one code enum and one public {@code error.code} on the
 * wire; the reason inside a kind is the code enum's constant (the wire's {@code details.reasonCode}). A tool boundary and the HTTP boundary decide
 * their answer from the kind with a complete {@code switch}, so a kind added here fails to compile until both answer it.
 */
public enum Category {
    /** The caller's input is wrong; changing it can make the call work. */
    INPUT("INVALID_INPUT"),
    /** A thing the call names does not exist (for the caller). */
    NOT_FOUND("NOT_FOUND"),
    /** The call is fine but meets another state or another writer; reading the state again, or retrying, can make it work. */
    CONFLICT("CONFLICT"),
    /** A rule of the product refuses this step now (a gate, a missing proof). */
    GATE("GATE_FAILED"),
    /** The call is not tied to a session or caller it needs, or the one it names is not valid. */
    BINDING("BINDING_INVALID"),
    /** Stored or received data contradicts itself or a constraint of the store (a reference that points nowhere, a failed digest). */
    INTEGRITY("INTEGRITY_FAILED"),
    /** The server cannot do this now (store down, limit reached); nothing the caller sent was wrong. */
    UNAVAILABLE("MCP_UNAVAILABLE");

    private final String wireCode;

    Category(String wireCode) {
        this.wireCode = wireCode;
    }

    /** The public {@code error.code} of the result envelope (contracts/api-result.v1.schema.json). */
    public String wireCode() {
        return wireCode;
    }
}

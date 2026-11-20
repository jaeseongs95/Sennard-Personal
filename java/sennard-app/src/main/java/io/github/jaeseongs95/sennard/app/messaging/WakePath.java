// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.messaging;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One row of {@code sennard.wake_paths}: per generation of a session ({@code instanceId} and {@code birthGeneration} as the identity area minted them), the
 * last time a message was claimed at a hook boundary of that generation. The boundary only moves forward ({@link WakePathRepository#moveBoundary}).
 */
@Table(schema = "sennard", value = "wake_paths")
public record WakePath(@Id @Embedded(onEmpty = Embedded.OnEmpty.USE_NULL) Key key, Instant claimBoundaryAt, Instant updatedAt, @Version Long version) {

    /** The session, then the generation. */
    public record Key(String sessionRef, String instanceId, String birthGeneration) {
    }

    /** A generation's row, made by the first claim boundary of it: that boundary is {@code now}. */
    public static WakePath created(Key key, Instant now) {
        return new WakePath(key, now, now, null);
    }
}

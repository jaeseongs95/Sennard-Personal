// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

/**
 * Work that must be finished before the personal edition's store is ready: what exists once in the whole server and that a request only reads (the keys and secrets that sign
 * and correlate, the row of the plugin's update state). It runs once the store is reached and migrated, on the one thread that prepares it, and a request is answered with
 * STORE_PREPARING until every such task has finished. A task that fails keeps the store from becoming ready: the attempt fails, is repeated, and the store's status carries the
 * reason ({@code /actuator/sennard}) after the last attempt. A task is idempotent: what already exists is left as it is, because an attempt can be repeated.
 *
 * <p>What can wait until the store is ready, and does not take the store down when it fails, is a {@link StoreReadyTask}.
 */
@FunctionalInterface
public interface StoreStartTask {
    void beforeStoreReady();
}

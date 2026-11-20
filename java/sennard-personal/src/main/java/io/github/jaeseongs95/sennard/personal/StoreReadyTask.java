// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

/**
 * Later work, for the moment the personal edition's store is ready (reached, migrated, and its {@link StoreStartTask}s finished). A failure is logged by the one that runs the
 * task and does not stop the others or take the store down: the store is ready and stays so. What a request cannot do without is a {@link StoreStartTask}.
 */
@FunctionalInterface
public interface StoreReadyTask {
    void whenStoreReady();
}

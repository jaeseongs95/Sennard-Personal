// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.hook.HostEventHandler.HookCall;

/** Which handler a call is for: a call of no module is every handler's, a call of a module is that module's handler's, and a module nobody knows is no handler's. */
class HookModuleTest {
    @Test
    void aCallOfNoModuleIsForEveryHandler() {
        HookCall call = new HookCall(null, null);
        for (HookModule module : HookModule.values()) {
            assertFalse(call.isForAnotherThan(module), module.name());
        }
    }

    @Test
    void aCallOfAModuleIsForThatModulesHandlerOnly() {
        assertFalse(new HookCall(null, "continuity").isForAnotherThan(HookModule.CONTINUITY));
        assertTrue(new HookCall(null, "continuity").isForAnotherThan(HookModule.PEER_WAIT));
        assertFalse(new HookCall(null, "peer-wait").isForAnotherThan(HookModule.PEER_WAIT));
        assertTrue(new HookCall(null, "peer-wait").isForAnotherThan(HookModule.CONTINUITY));
    }

    @Test
    void aModuleNoHandlerKnowsIsForNoOne() {
        for (HookModule module : HookModule.values()) {
            assertTrue(new HookCall(null, "other").isForAnotherThan(module), module.name());
        }
    }
}

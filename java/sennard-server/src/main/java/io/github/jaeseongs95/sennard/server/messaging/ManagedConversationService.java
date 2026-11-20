// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.app.operation.OperationReceipts;
import io.github.jaeseongs95.sennard.app.operation.Receipted;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol.LaunchReply;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol.LaunchResult;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;

@Service
public class ManagedConversationService {
    private final IdleWakeService wake;
    private final OperationReceipts receipts;

    public ManagedConversationService(IdleWakeService wake, OperationReceipts receipts) {
        this.wake = wake;
        this.receipts = receipts;
    }

    @Transactional(propagation = Propagation.NEVER)
    public LaunchReply launch(SelfSession caller, String model, String effort, OperationKey operation) {
        wake.requireManagedOwner(caller);
        // Once the host has been contacted a crash can leave a real child. Commit UNKNOWN before sending the request.
        var prior = receipts.reserveExternal(operation, LaunchReply.class, new LaunchReply(LaunchResult.UNKNOWN, null));
        if (prior instanceof Receipted.Replayed<LaunchReply> replayed) {
            return replayed.value();
        }
        LaunchReply reply = wake.launchManagedChild(caller, model, effort);
        receipts.complete(operation, reply);
        return reply;
    }
}

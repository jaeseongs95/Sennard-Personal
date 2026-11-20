// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import io.github.jaeseongs95.sennard.server.contract.ApiResult;
import io.github.jaeseongs95.sennard.server.message.Messages;

/** The public body of a contract error: the code, the sentence (model language), its catalog key and arguments, and the sanitized details. */
final class WorkflowResults {
    private WorkflowResults() {
    }

    static ApiResult.ErrorBody errorBody(Messages messages, WorkflowFailure failure) {
        return new ApiResult.ErrorBody(failure.code().name(), messages.render(failure.ref()), failure.ref().key(), failure.ref().params(), failure.details());
    }
}

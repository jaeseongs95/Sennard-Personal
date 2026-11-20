// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.journal;

import java.util.List;
import org.springframework.data.repository.Repository;

public interface ScanFailureRepository extends Repository<ScanFailure, String> {
    List<ScanFailure> findByWorkflowId(String workflowId);
    long deleteByWorkflowId(String workflowId);
    ScanFailure save(ScanFailure row);
}

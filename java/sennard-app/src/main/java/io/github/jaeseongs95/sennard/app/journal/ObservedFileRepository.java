// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.journal;

import java.util.List;
import org.springframework.data.repository.Repository;

public interface ObservedFileRepository extends Repository<ObservedFile, String> {
    List<ObservedFile> findByWorkflowId(String workflowId);
    ObservedFile save(ObservedFile row);
}

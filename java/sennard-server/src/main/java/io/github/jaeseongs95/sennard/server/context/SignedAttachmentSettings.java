// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentProperties;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentSettings;

/** The server and offline maintenance read the same verified settings, including earlier body locations. */
@Service
@Primary
public class SignedAttachmentSettings extends AttachmentSettings {
    private final AdminLogSource logs;
    private final AdminLogVerifier verifier;
    public SignedAttachmentSettings(AttachmentProperties properties, AdminLogSource logs, AdminLogVerifier verifier) {
        super(properties); this.logs = logs; this.verifier = verifier;
    }
    @Override
    @Transactional(readOnly = true)
    public Snapshot current() {
        Snapshot defaults = super.current();
        // ceiling: each lookup verifies the full signed log; if growth makes this costly, cache a verified prefix with regression checks.
        AdminLog.State state = verifier.fold(logs.records());
        if (state.attachmentStorage == null) return defaults;
        AttachmentStorageSettings value = state.attachmentStorage.settings();
        LinkedHashSet<Path> roots = new LinkedHashSet<>(defaults.directories());
        state.attachmentDirectories.stream().map(Path::of).map(path -> path.toAbsolutePath().normalize()).forEach(roots::add);
        Path current = Path.of(value.directory()).toAbsolutePath().normalize();
        roots.add(current);
        return new Snapshot(current, roots.stream().toList(), value.maxFileBytes(), value.stagingBudgetBytes(),
                value.attachmentBudgetBytes(), value.maxOpenPerOwner(), value.diskMarginBytes());
    }
}

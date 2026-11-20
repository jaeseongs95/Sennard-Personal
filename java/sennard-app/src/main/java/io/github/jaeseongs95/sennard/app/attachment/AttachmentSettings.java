// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/** Runtime attachment policy. The property implementation is also used by isolated storage tests. */
@Component
public class AttachmentSettings {
    public record Snapshot(Path directory, List<Path> directories, long maxFileBytes, long stagingBudgetBytes,
            long attachmentBudgetBytes, int maxOpenPerOwner, long diskMarginBytes) {
        public Snapshot { directories = List.copyOf(directories); }
    }
    private final AttachmentProperties properties;
    public AttachmentSettings(AttachmentProperties properties) { this.properties = properties; }
    public Snapshot current() {
        Path root = properties.directory() == null ? null : properties.directory().toAbsolutePath().normalize();
        return new Snapshot(root, root == null ? List.of() : List.of(root), properties.maxFileBytes().toBytes(),
                properties.stagingBudgetBytes().toBytes(), properties.attachmentBudgetBytes().toBytes(),
                properties.maxOpenPerOwner(), properties.diskMarginBytes().toBytes());
    }
}

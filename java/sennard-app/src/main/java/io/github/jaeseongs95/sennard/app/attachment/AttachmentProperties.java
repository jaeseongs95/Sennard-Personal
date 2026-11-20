// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The attachment settings ({@code sennard.attachments.*}), the one record of the prefix: the keys the design of the three products names (docs/contributing/attachments.md, 3.1), with the
 * same names and the same defaults. A size is Spring's {@link DataSize}: {@code 64MB} is 64 mebibytes (1,048,576 bytes each), the unit the design's MiB means.
 *
 * <p>{@code directory} and {@code keyDirectory} have no default in code: they are paths of the installation, so {@code application.yaml} gives them under the state folder
 * ({@code ${sennard.state-dir}/attachments} and {@code ${sennard.state-dir}/secrets/attachment-keys}), and a context without them has no folder to write; the file store refuses to start then
 * ({@link StorageFolders}, {@link AttachmentKeys}). The key folder inside the attachment folder is refused at the start of the settings themselves, because a copy of the bodies would then carry the keys.
 *
 * @param directory where the bodies of the attachments are written
 * @param keyDirectory where the key files are; outside {@code directory}
 * @param activeKeyId the key new uploads are written with; {@value #FIRST_KEY_ID} (the key the first start makes) when not set
 * @param maxFileBytes the largest file of one attachment
 * @param stagingBudgetBytes the most the open uploads may declare together
 * @param attachmentBudgetBytes the most the kept attachments, with the declared size of the open uploads and the new upload, may be together
 * @param maxOpenPerOwner how many uploads one author may have open
 * @param diskMarginBytes the space the disk must keep once a file is written; short of it a write is refused as ARTIFACT_STORAGE_FULL. The default is 64 MiB, independently
 *     configurable from the file limit (a resource policy, not a measurement)
 * @param uploadTtl how long after the last chunk an upload expires
 * @param orphanGrace how long a file without a row waits before it is moved to {@code orphans/}
 * @param reaperInterval how often the cleaner runs: it finds the expired upload rows and the done upload rows in the database and deletes them (a lookup of rows, never a walk of the folder)
 * @param reconcileInterval how often the folder is compared with the rows (the design's 7: files with no row, replaced files, the quarantine folder), besides at the start and by
 *     {@code sennard attachments purge-orphans}. The comparison looks at every file of the folder with one to three lookups each, so it is not run every minute like the cleaner. It is
 *     24 hours by default, the same as the default of {@code orphanGrace}: a file with no row, and a file that a later file replaced, are moved only once
 *     {@code orphanGrace} has passed, so a comparison more often than that finds the same files again and gains nothing, and one run per grace moves a file at most one interval late (a
 *     judgement, not a measurement)
 */
@ConfigurationProperties("sennard.attachments")
@Validated
public record AttachmentProperties(
        Path directory,
        Path keyDirectory,
        String activeKeyId,
        @DefaultValue("1GB") @NotNull DataSize maxFileBytes,
        @DefaultValue("4GB") @NotNull DataSize stagingBudgetBytes,
        @DefaultValue("10GB") @NotNull DataSize attachmentBudgetBytes,
        @DefaultValue("4") @Min(1) int maxOpenPerOwner,
        @DefaultValue("64MB") @NotNull DataSize diskMarginBytes,
        @DefaultValue("10m") @NotNull Duration uploadTtl,
        @DefaultValue("24h") @NotNull Duration orphanGrace,
        @DefaultValue("1m") @NotNull Duration reaperInterval,
        @DefaultValue("24h") @NotNull Duration reconcileInterval) {
    /** The name of the key the first start makes. */
    public static final String FIRST_KEY_ID = "primary";

    public AttachmentProperties {
        if (directory != null && keyDirectory != null) {
            Path files = directory.toAbsolutePath().normalize();
            Path keys = keyDirectory.toAbsolutePath().normalize();
            if (keys.startsWith(files)) {
                throw new IllegalArgumentException("sennard.attachments.key-directory must be outside sennard.attachments.directory");
            }
        }
        if (activeKeyId != null && !AttachmentKeys.validId(activeKeyId)) {
            throw new IllegalArgumentException("sennard.attachments.active-key-id is not a key id");
        }
        if (maxFileBytes.toBytes() < 1 || uploadTtl.isNegative() || uploadTtl.isZero() || orphanGrace.isNegative() || reaperInterval.isNegative() || reaperInterval.isZero()
                || reconcileInterval.isNegative() || reconcileInterval.isZero()) {
            throw new IllegalArgumentException("sennard.attachments: a size must be at least one byte and a time must be positive");
        }
    }

    /** The key id new uploads are written with. */
    public String activeKey() {
        return activeKeyId == null ? FIRST_KEY_ID : activeKeyId;
    }
}

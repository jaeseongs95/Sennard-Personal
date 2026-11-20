// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

/**
 * The settings of the attachments ({@code sennard.attachments.*}, design 3.1), bound the way Spring Boot binds them, with no database.
 *
 * <h2>Test cases (design test numbers of section 9)</h2>
 * <pre>
 * 10/17  p1  defaults        every key has the design's default: max-file 1 GiB, staging budget 4 GiB, attachment budget 10 GiB, 4 open uploads per owner, disk margin 64 MiB
 *                            (independent of the file limit), upload TTL 10 minutes, orphan grace 24 hours, reaper interval 1 minute, and the active key is "primary" when none is named
 * 14     p2  active key      a named active key is the one new uploads use
 * 3.1    p3  key folder      a key-directory inside directory (or the same folder) fails the settings; a sibling whose name only starts with the same letters is outside
 * 3.1    p4  relaxed binding names bind in kebab case with units ({@code 1KB}, {@code 30s})
 * 3.1    p5  limits          a file limit below one byte, a time that is not positive and an active key that cannot be a file name fail the settings
 * </pre>
 *
 * The design gives {@code directory} and {@code key-directory} no value of the code but a place under the installation's data folder; sennard-personal's {@code application.yaml} states
 * them ({@code ${sennard.state-dir}/attachments} and {@code ${sennard.state-dir}/secrets/attachment-keys}), so here they are null until set.
 */
class AttachmentPropertiesTest {
    @TempDir
    Path scratch;

    private static AttachmentProperties bind(Map<String, String> settings) {
        return new Binder(new MapConfigurationPropertySource(settings)).bind("sennard.attachments", Bindable.of(AttachmentProperties.class)).get();
    }

    private AttachmentProperties with(Path directory, Path keyDirectory) {
        return new AttachmentProperties(directory, keyDirectory, null, DataSize.ofMegabytes(64), DataSize.ofMegabytes(512), DataSize.ofGigabytes(10), 4, DataSize.ofMegabytes(64),
                Duration.ofMinutes(10), Duration.ofHours(24), Duration.ofMinutes(1), Duration.ofHours(24));
    }

    @Test
    void everyKeyHasTheDefaultOfTheDesign() {
        AttachmentProperties properties = bind(Map.of("sennard.attachments.max-open-per-owner", "4"));

        assertEquals(1024L * 1024 * 1024, properties.maxFileBytes().toBytes());
        assertEquals(4L * 1024 * 1024 * 1024, properties.stagingBudgetBytes().toBytes());
        assertEquals(10L * 1024 * 1024 * 1024, properties.attachmentBudgetBytes().toBytes());
        assertEquals(4, properties.maxOpenPerOwner());
        assertEquals(64L * 1024 * 1024, properties.diskMarginBytes().toBytes());
        assertEquals(Duration.ofMinutes(10), properties.uploadTtl());
        assertEquals(Duration.ofHours(24), properties.orphanGrace());
        assertEquals(Duration.ofMinutes(1), properties.reaperInterval());
        assertEquals(Duration.ofHours(24), properties.reconcileInterval(), "the comparison of the folder with the rows runs once per orphan-grace, not every minute like the cleaner");
        assertEquals(properties.orphanGrace(), properties.reconcileInterval());
        assertNull(properties.activeKeyId());
        assertEquals("primary", properties.activeKey(), "the key the first start makes");
        assertNull(properties.directory());
        assertNull(properties.keyDirectory());
    }

    @Test
    void aNamedActiveKeyIsTheOneNewUploadsUse() {
        AttachmentProperties properties = bind(Map.of("sennard.attachments.active-key-id", "key-2026"));

        assertEquals("key-2026", properties.activeKey());
    }

    @Test
    void theKeysAreBoundInKebabCaseWithUnits() {
        AttachmentProperties properties = bind(Map.of("sennard.attachments.max-file-bytes", "1KB", "sennard.attachments.upload-ttl", "30s", "sennard.attachments.reaper-interval", "5m",
                "sennard.attachments.orphan-grace", "2h", "sennard.attachments.max-open-per-owner", "2", "sennard.attachments.attachment-budget-bytes", "3KB", "sennard.attachments.reconcile-interval", "6h"));

        assertEquals(1024, properties.maxFileBytes().toBytes());
        assertEquals(Duration.ofSeconds(30), properties.uploadTtl());
        assertEquals(Duration.ofMinutes(5), properties.reaperInterval());
        assertEquals(Duration.ofHours(6), properties.reconcileInterval());
        assertEquals(Duration.ofHours(2), properties.orphanGrace());
        assertEquals(2, properties.maxOpenPerOwner());
        assertEquals(3072, properties.attachmentBudgetBytes().toBytes());
    }

    @Test
    void aKeyFolderInsideTheAttachmentFolderFailsTheSettings() {
        Path files = scratch.resolve("attachments");

        IllegalArgumentException inside = assertThrows(IllegalArgumentException.class, () -> with(files, files.resolve("keys")));
        assertEquals("sennard.attachments.key-directory must be outside sennard.attachments.directory", inside.getMessage());
        assertThrows(IllegalArgumentException.class, () -> with(files, files), "the same folder is inside");
        assertThrows(IllegalArgumentException.class, () -> with(files, files.resolve("a").resolve("b").resolve("keys")), "any depth is inside");
        assertThrows(IllegalArgumentException.class, () -> with(files, files.resolve("x").resolve("..").resolve("keys")), "a path that only reaches the folder by '..' is looked at normalized");
    }

    @Test
    void aKeyFolderOutsideTheAttachmentFolderIsAccepted() {
        Path files = scratch.resolve("attachments");

        assertDoesNotThrow(() -> with(files, scratch.resolve("keys")));
        assertDoesNotThrow(() -> with(files, scratch.resolve("attachments-keys")), "a name that starts with the same letters is another folder");
        assertDoesNotThrow(() -> with(files, scratch.resolve("secrets").resolve("attachment-keys")));
        assertDoesNotThrow(() -> with(files, null), "the folders are the installation's: not set, not compared");
    }

    @Test
    void aLimitBelowOneByteATimeThatIsNotPositiveAndAnUnusableKeyNameFailTheSettings() {
        assertThrows(IllegalArgumentException.class, () -> new AttachmentProperties(null, null, null, DataSize.ofBytes(0), DataSize.ofMegabytes(512), DataSize.ofGigabytes(10), 4,
                DataSize.ofBytes(0), Duration.ofMinutes(10), Duration.ofHours(24), Duration.ofMinutes(1), Duration.ofHours(24)));
        assertThrows(IllegalArgumentException.class, () -> new AttachmentProperties(null, null, null, DataSize.ofMegabytes(64), DataSize.ofMegabytes(512), DataSize.ofGigabytes(10), 4,
                DataSize.ofBytes(0), Duration.ZERO, Duration.ofHours(24), Duration.ofMinutes(1), Duration.ofHours(24)));
        assertThrows(IllegalArgumentException.class, () -> new AttachmentProperties(null, null, null, DataSize.ofMegabytes(64), DataSize.ofMegabytes(512), DataSize.ofGigabytes(10), 4,
                DataSize.ofBytes(0), Duration.ofMinutes(10), Duration.ofHours(-1), Duration.ofMinutes(1), Duration.ofHours(24)));
        assertThrows(IllegalArgumentException.class, () -> new AttachmentProperties(null, null, null, DataSize.ofMegabytes(64), DataSize.ofMegabytes(512), DataSize.ofGigabytes(10), 4,
                DataSize.ofBytes(0), Duration.ofMinutes(10), Duration.ofHours(24), Duration.ZERO, Duration.ofHours(24)));
        assertThrows(IllegalArgumentException.class, () -> new AttachmentProperties(null, null, null, DataSize.ofMegabytes(64), DataSize.ofMegabytes(512), DataSize.ofGigabytes(10), 4,
                DataSize.ofBytes(0), Duration.ofMinutes(10), Duration.ofHours(24), Duration.ofMinutes(1), Duration.ZERO), "a comparison of the folder that is never due is no setting");
        assertThrows(IllegalArgumentException.class, () -> new AttachmentProperties(null, null, null, DataSize.ofMegabytes(64), DataSize.ofMegabytes(512), DataSize.ofGigabytes(10), 4,
                DataSize.ofBytes(0), Duration.ofMinutes(10), Duration.ofHours(24), Duration.ofMinutes(1), Duration.ofHours(-1)));
        assertThrows(IllegalArgumentException.class, () -> new AttachmentProperties(null, null, "../outside", DataSize.ofMegabytes(64), DataSize.ofMegabytes(512), DataSize.ofGigabytes(10),
                4, DataSize.ofBytes(0), Duration.ofMinutes(10), Duration.ofHours(24), Duration.ofMinutes(1), Duration.ofHours(24)), "a key id is a file name, never a path");
    }
}

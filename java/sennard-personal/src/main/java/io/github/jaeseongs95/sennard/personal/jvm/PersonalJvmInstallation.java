// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.jvm;

import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.github.jaeseongs95.sennard.domain.jvm.JvmPreset;
import io.github.jaeseongs95.sennard.personal.PersonalProperties;
import io.github.jaeseongs95.sennard.personal.StoreReadyTask;
import io.github.jaeseongs95.sennard.personal.install.InstalledVersion;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.personal.install.JvmSettingsFile;
import io.github.jaeseongs95.sennard.server.admin.JvmInstallation;
import io.github.jaeseongs95.sennard.server.context.AdminLog;
import io.github.jaeseongs95.sennard.server.context.ContextLayerService;
import io.github.jaeseongs95.sennard.server.context.JvmPresetSigned;

/**
 * The personal edition's JVM preset: the file beside the jar. The preset is signed on the admin page and the JVM's
 * options are fixed before the server starts, so a signed change is written to {@code sennard-jvm.yaml} once its commit is done (the installer makes the server's options from that file at each start),
 * and the next start uses it. A reinstall or an upgrade starts from the installer's own choice; {@link #reconcile} puts the signed one back as soon as the store is ready.
 * Both write only for an installed version ({@code <root>/versions/<version>}): a server run from a folder that was only extracted is not started by the installer, so no file would be read.
 */
@Component
public class PersonalJvmInstallation implements JvmInstallation, StoreReadyTask {
    private static final Logger LOG = LoggerFactory.getLogger(PersonalJvmInstallation.class);

    private final InstalledVersion image;
    private final ContextLayerService context;

    PersonalJvmInstallation(PersonalProperties properties, ContextLayerService context) {
        this.image = InstalledVersion.ofFolder(properties.home());
        this.context = context;
    }

    @Override
    public Optional<Saved> saved() {
        try {
            return JvmSettingsFile.read(image.jvmSettingsFile()).map(content -> new Saved(content.preset(), content.observedBytes()));
        } catch (SennardException unreadable) {
            LOG.warn("The JVM settings file cannot be read; the page shows no saved preset.", unreadable);
            return Optional.empty();
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void whenSigned(JvmPresetSigned signed) {
        apply(signed.preset());
    }

    /** When the store is ready: a signed choice that the file does not hold (after a reinstall or an upgrade) is written for the next start. */
    @Override
    public void whenStoreReady() {
        reconcile();
    }

    public void reconcile() {
        AdminLog.JvmPresetState signed = context.verifiedState().jvmPreset;
        if (signed != null && saved().map(value -> value.preset() != signed.preset()).orElse(true)) {
            LOG.info("The signed JVM preset {} is not the one the settings file holds; it applies from the next start.", signed.preset());
            apply(signed.preset());
        }
    }

    private void apply(JvmPreset preset) {
        image.installRoot().ifPresentOrElse(root -> {
            try {
                JvmSettingsFile.Content current = JvmSettingsFile.read(image.jvmSettingsFile()).orElse(new JvmSettingsFile.Content(preset, null));
                JvmSettingsFile.write(image.jvmSettingsFile(), current.withPreset(preset));
                LOG.info("The JVM preset {} is written to the settings file; it applies when the server is restarted.", preset);
            } catch (SennardException failed) {
                LOG.error("The JVM preset {} could not be written for the next start; the start after a restart still uses the previous one ({}).", preset, failed.code(), failed);
            }
        }, () -> LOG.info("The JVM preset {} is saved on the admin page but this server does not run from an installed version, so no settings file is written.", preset));
    }
}

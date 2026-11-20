// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;

/**
 * Makes the state directory on start, private to its owner when it is made, and refuses one that is not a directory. The personal edition trusts the PC, so a directory that exists
 * is used as it is: its permissions and its links are not looked at, and nothing is repaired.
 */
@Component
final class LocalStateDirectory {
    LocalStateDirectory(PersonalProperties properties, PrivateStorage storage) throws IOException {
        Path directory = properties.stateDir().toAbsolutePath().normalize();
        storage.createFolder(directory);
        storage.requireSafeFolder(directory);
    }
}

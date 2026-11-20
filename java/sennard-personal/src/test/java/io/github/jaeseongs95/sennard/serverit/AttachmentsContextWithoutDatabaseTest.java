// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.attachmentcli.AttachmentsContext;
import io.github.jaeseongs95.sennard.attachmentcli.AttachmentsRequest;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;

/**
 * {@code sennard attachments} in an installation with no business database linked (the settings database of the state folder holds no link; a {@code sennard.store.database.*} key is no
 * source): the command's context does not start, and the failure is an {@link UnavailableException} of the command's own kind (COMMAND_NOT_STARTED) that names the command and what is
 * missing, not the stack of the context's start. It needs no database.
 */
class AttachmentsContextWithoutDatabaseTest {
    @Test
    void withoutABusinessDatabaseTheCommandIsNotStartedAndSaysWhich(@TempDir Path home) {
        UnavailableException failure = assertThrows(UnavailableException.class,
                () -> AttachmentsContext.run(new AttachmentsRequest.Check(), home, "--sennard.state-dir=" + home, "--sennard.attachments.directory=" + home.resolve("attachments"),
                        "--sennard.attachments.key-directory=" + home.resolve("keys"), "--sennard.store.database.url=jdbc:postgresql://127.0.0.1:1/none"));

        assertEquals(UnavailableCode.COMMAND_NOT_STARTED, failure.code());
        assertEquals("sennard attachments", failure.arguments().get("command"));
        assertEquals("No business database is linked on the admin page.", failure.arguments().get("cause"));
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/** Writes THIRD_PARTY_NOTICES.md (UTF-8, LF line ends) to the file it is given. */
public abstract class GenerateThirdPartyNoticesTask extends ThirdPartyNoticesTask {
    @OutputFile
    public abstract RegularFileProperty getNoticesFile();

    @TaskAction
    public void generate() {
        try {
            Files.writeString(getNoticesFile().get().getAsFile().toPath(), render(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GradleException("cannot write " + getNoticesFile().get().getAsFile(), e);
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Fails when THIRD_PARTY_NOTICES.md in the repository differs from the notices of the current dependencies. The bytes are compared as they are: .gitattributes ({@code * text=auto eol=lf}) makes every checkout LF on every OS. */
public abstract class CheckThirdPartyNoticesTask extends ThirdPartyNoticesTask {
    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getNoticesFile();

    @TaskAction
    public void check() {
        String expected = render();
        String found;
        try {
            found = Files.readString(getNoticesFile().get().getAsFile().toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GradleException("cannot read " + getNoticesFile().get().getAsFile(), e);
        }
        if (!expected.equals(found)) {
            throw new GradleException(getNoticesFile().get().getAsFile().getName() + " is not current with the dependencies of the shipped class path. "
                    + "Run `./gradlew :sennard-personal:generateThirdPartyNotices` in java/ and commit the file.");
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.build;

import groovy.json.JsonSlurper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

/**
 * The text of THIRD_PARTY_NOTICES.md as the license report of the Gradle-License-Report plugin states it: its file {@code project-licenses-for-check-license-task.json}, written by the
 * same run as {@code checkLicense}, has every dependency of the checked class path with the license names after the plugin's normalization, so the notices and the allow-list check
 * read one source. The shape is the plugin's {@code JsonReportRenderer} with all licenses per module (https://github.com/jk1/Gradle-License-Report).
 */
public abstract class ThirdPartyNoticesTask extends DefaultTask {
    /** The report file of the plugin that lists every dependency with all of its licenses. */
    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getLicenseData();

    /** The notices as they would be written now. */
    protected final String render() {
        File data = getLicenseData().get().getAsFile();
        Object parsed = new JsonSlurper().parse(data);
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("dependencies") instanceof List<?> dependencies)) {
            throw new GradleException(data + " has no \"dependencies\" list: the license report changed its shape");
        }
        List<ThirdPartyNotices.Entry> entries = new ArrayList<>();
        for (Object dependency : dependencies) {
            if (!(dependency instanceof Map<?, ?> module)) {
                throw new GradleException(data + " has a dependency that is not an object: " + dependency);
            }
            List<String> licenses = new ArrayList<>();
            if (module.get("moduleLicenses") instanceof List<?> found) {
                for (Object license : found) {
                    if (license instanceof Map<?, ?> named && named.get("moduleLicense") != null) {
                        licenses.add(named.get("moduleLicense").toString().strip());
                    }
                }
            }
            List<String> urls = new ArrayList<>();
            if (module.get("moduleUrls") instanceof List<?> found) {
                found.forEach(url -> urls.add(url.toString().strip()));
            }
            if (module.get("moduleUrl") != null) {
                urls.add(module.get("moduleUrl").toString().strip());
            }
            entries.add(new ThirdPartyNotices.Entry(String.valueOf(module.get("moduleName")), String.valueOf(module.get("moduleVersion")),
                    licenses.stream().distinct().sorted().toList(), urls.stream().distinct().sorted().toList()));
        }
        return ThirdPartyNotices.render(entries);
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import io.github.jaeseongs95.sennard.attachmentcli.AttachmentsEntrance;

/**
 * The server's component scan does not reach the offline {@code sennard attachments}: {@code AttachmentsEntrance} is a {@code @Component} and its context class a {@code @Configuration},
 * and the server has neither as a bean. The packages scanned are read from the server's own {@code @SpringBootApplication}, so a change of that list is judged here; no exclusion
 * filter is involved, the classes are in a package that the scan does not cover. The server does have the classes the command imports (the upkeep, the service), which are in the
 * scanned application layer: only the command's own are kept out.
 *
 * <pre>
 * s1  the scan is real   the scan of the server's packages finds the store configuration of the personal edition and the upkeep of the attachment folder (the test would pass on an
 *                        empty scan otherwise)
 * s2  not scanned        it finds no class of the package of AttachmentsEntrance and its context configuration (AttachmentsConfiguration is not visible from here, so the package is judged)
 * </pre>
 */
class AttachmentsScanTest {
    private static List<String> scanned() {
        String[] packages = SennardPersonalApplication.class.getAnnotation(SpringBootApplication.class).scanBasePackages();
        ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(true);
        return Stream.of(packages).flatMap(base -> provider.findCandidateComponents(base).stream()).map(definition -> definition.getBeanClassName()).toList();
    }

    @Test
    void s1TheScanOfTheServersPackagesFindsItsComponents() {
        List<String> found = scanned();
        assertTrue(found.contains(PersonalStoreConfiguration.class.getName()), found.toString());
        assertTrue(found.contains("io.github.jaeseongs95.sennard.app.attachment.AttachmentMaintenance"), found.toString());
    }

    @Test
    void s2TheServersScanFindsNeitherTheAttachmentsEntranceNorItsContextConfiguration() {
        List<String> found = scanned();
        assertFalse(found.contains(AttachmentsEntrance.class.getName()), "AttachmentsEntrance is a bean of the server: " + found);
        assertEquals(List.of(), found.stream().filter(name -> name.startsWith(AttachmentsEntrance.class.getPackageName() + ".")).toList());
    }
}

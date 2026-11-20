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

import io.github.jaeseongs95.sennard.admincli.AdminReset;

/**
 * The server's component scan does not reach the offline admin reset: {@code AdminReset} is a {@code @Service} and its context class a {@code @Configuration}, and the server
 * has neither as a bean. The packages scanned are read from the server's own {@code @SpringBootApplication}, so a change of that list is judged here; no exclusion filter is
 * involved, the classes are in a package that the scan does not cover.
 *
 * <pre>
 * a1  the scan is real   the scan of the server's packages finds the store configuration of the personal edition (the test would pass on an empty scan otherwise)
 * a2  not scanned        it finds no class of the package of AdminReset and its context configuration (AdminResetConfiguration is not visible from here, so the package is judged)
 * </pre>
 */
class AdminResetScanTest {
    private static List<String> scanned() {
        String[] packages = SennardPersonalApplication.class.getAnnotation(SpringBootApplication.class).scanBasePackages();
        ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(true);
        return Stream.of(packages).flatMap(base -> provider.findCandidateComponents(base).stream()).map(definition -> definition.getBeanClassName()).toList();
    }

    @Test
    void a1TheScanOfTheServersPackagesFindsItsComponents() {
        assertTrue(scanned().contains(PersonalStoreConfiguration.class.getName()), scanned().toString());
    }

    @Test
    void a2TheServersScanFindsNeitherTheAdminResetNorItsContextConfiguration() {
        List<String> found = scanned();
        assertFalse(found.contains(AdminReset.class.getName()), "AdminReset is a bean of the server: " + found);
        assertEquals(List.of(), found.stream().filter(name -> name.startsWith(AdminReset.class.getPackageName())).toList());
    }
}

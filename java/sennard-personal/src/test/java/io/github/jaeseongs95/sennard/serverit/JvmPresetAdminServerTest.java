// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.domain.jvm.Collector;
import io.github.jaeseongs95.sennard.domain.jvm.JvmPreset;
import io.github.jaeseongs95.sennard.personal.install.InstalledVersion;
import io.github.jaeseongs95.sennard.personal.install.JvmOptions;
import io.github.jaeseongs95.sennard.personal.install.JvmSettingsFile;
import io.github.jaeseongs95.sennard.personal.jvm.PersonalJvmInstallation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The server's JVM preset on the admin page, with the real signed log and a server that runs from an installed version's folder ({@code <root>/versions/<version>}, which holds
 * {@code sennard-jvm.yaml}). The one file the preset lives in is {@code sennard-jvm.yaml}: the installer makes the server's JVM options from it at each start ({@link JvmOptions#server}),
 * so what a signed change does is write that file, and "the next start's options" are what {@code JvmOptions.server} makes from the file as it is now. (There is no launcher
 * configuration: the server is started by the installer with the options it makes, not by a program that holds a copy of them.)
 *
 * <pre>
 * a1  shown      the state lists the seven presets, the saved one as current, nothing pending, and the memory as an estimate in the request's language
 *                (en: 29.7 GB reported is shown as 32 GB; ko: the Korean sentence)
 * a2  signed     a passkey-signed JVM_PRESET_SET is written to sennard-jvm.yaml (the observed memory kept), and the options the next start is given are the new preset's (its collector,
 *                its heap, the AOT cache of its collector) once it is committed
 * a3  not signed a preset that is not in the closed set, whether a name or a JVM option, is refused before any signature is asked for and the file stays as it was
 * a4  reconcile  a file that holds another preset than the signed one (a reinstall wrote the installer's choice) is put back to the signed one when the store is ready
 * </pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("business-db")
class JvmPresetAdminServerTest {
    private static final long OBSERVED = (long) (29.7 * (1L << 30));

    private static ToolServerSupport sennard;
    private static ToolServerSupport.Passkey owner;
    private static InstalledVersion image;
    @TempDir
    private static Path root; // the installation these tests write the settings file of; JUnit deletes it after the class (D-0194)

    @BeforeAll
    static void start() throws Exception {
        image = new InstalledVersion(root.resolve(InstalledVersion.VERSIONS).resolve("6.0.0"));
        Files.createDirectories(image.folder());
        JvmSettingsFile.write(image.jvmSettingsFile(), new JvmSettingsFile.Content(JvmPreset.G1_512M, OBSERVED));
        sennard = ToolServerSupport.startWith("--sennard.home=" + image.folder());
        owner = sennard.owner();
    }

    @AfterAll
    static void stop() {
        sennard.close();
    }

    private JsonNode jvm() throws Exception {
        return sennard.state().path("settings").path("jvm");
    }

    private JsonNode jvm(String language) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", "localhost:" + sennard.adminPort);
        headers.put("Accept-Language", language);
        return SessionServerSupport.json(RawHttp.exchange(sennard.adminPort, "GET", "/api/state", headers, null)).path("settings").path("jvm");
    }

    private static ObjectNode change(String preset) {
        return ToolServerSupport.MAPPER.createObjectNode().put("op", "JVM_PRESET_SET").put("preset", preset);
    }

    /** The JVM options the installer would start the server with now: made from the preset the settings file holds, as {@code Installer} makes them at each start. */
    private static List<String> nextStartOptions() {
        return JvmOptions.server(JvmSettingsFile.read(image.jvmSettingsFile()).orElseThrow().preset(), image);
    }

    @Test
    @Order(1)
    void theStateShowsTheClosedSetTheSavedPresetAndTheMemoryAsAnEstimateInTheRequestsLanguage() throws Exception {
        JsonNode state = jvm();
        assertEquals(List.of("G1_224M", "G1_512M", "G1_1G", "G1_2G", "G1_4G", "ZGC_4G", "ZGC_6_PERCENT"), state.path("presets").valueStream().map(item -> item.path("id").asString()).toList());
        assertEquals("G1_512M", state.path("saved").asString());
        assertEquals("G1_512M", state.path("current").asString());
        assertTrue(state.path("signed").isNull(), state.toString());
        assertFalse(state.path("pending").asBoolean(true));
        assertEquals(32, state.path("estimate").path("estimatedGigabytes").asInt());
        assertEquals("G1_2G", state.path("estimate").path("defaultPreset").asString());
        // The wording is the catalog's (domain admin, key admin.jvm.estimate; JvmEstimatePatternTest fixes how it renders): the page holds the plan's sentence.
        assertEquals("Estimated memory 32GB (calculated from the 29.7GB capacity confirmed by the JVM. It may differ from the RAM actually installed.)",
                state.path("estimate").path("message").asString());
        assertEquals("추정 메모리 32GB (JVM에서 확인된 용량 29.7GB 기준으로 산정되었습니다. 실제 장착된 RAM 용량과 다를 수 있습니다.)",
                jvm("ko").path("estimate").path("message").asString());
    }

    @Test
    @Order(2)
    void aPresetThatIsNotInTheClosedSetIsRefusedAndNothingIsWritten() throws Exception {
        for (String bad : List.of("-Xmx64g", "g1_2g", "G1_2G -Xmx64g")) {
            ObjectNode body = ToolServerSupport.MAPPER.createObjectNode();
            body.set("change", change(bad));
            body.put("bootstrap", false);
            RawHttp.Response refused = sennard.post("/api/intent", body);
            assertEquals(400, refused.status(), refused.text());
            assertEquals("ADMIN_CHANGE_INVALID", SessionServerSupport.json(refused).path("code").asString(), bad);
        }
        assertEquals(JvmPreset.G1_512M, JvmSettingsFile.read(image.jvmSettingsFile()).orElseThrow().preset());
        assertTrue(nextStartOptions().contains("-Xmx512m"), "the options of the next start are as they were: " + nextStartOptions());
    }

    @Test
    @Order(3)
    void aSignedPresetIsWrittenForTheNextStartAndTheMemoryReadingIsKept() throws Exception {
        sennard.sign(owner, change("ZGC_6_PERCENT"));
        JvmSettingsFile.Content saved = JvmSettingsFile.read(image.jvmSettingsFile()).orElseThrow();
        assertEquals(new JvmSettingsFile.Content(JvmPreset.ZGC_6_PERCENT, OBSERVED), saved);
        // The preset the file holds is what the installer makes the next start's options from: the new collector and heap, the AOT cache of the new collector (a cache is made for one
        // collector), and what every preset shares; nothing of the old preset is left.
        List<String> next = nextStartOptions();
        assertTrue(next.contains("-XX:+UseZGC") && next.contains("-XX:MaxRAMPercentage=6") && next.contains("-XX:AOTCache=" + image.aotCache(Collector.ZGC)) && next.contains("-Duser.timezone=UTC"), next.toString());
        assertEquals("app-zgc.aot", image.aotCache(Collector.ZGC).getFileName().toString(), "the cache of the collector is its own file");
        assertFalse(next.contains("-Xmx512m") || next.contains("-XX:+UseG1GC"), next.toString());
        JsonNode state = jvm();
        assertEquals("ZGC_6_PERCENT", state.path("signed").asString());
        assertEquals("ZGC_6_PERCENT", state.path("saved").asString());
        assertFalse(state.path("pending").asBoolean(true));
        assertEquals(32, state.path("estimate").path("estimatedGigabytes").asInt(), "the estimate is of the memory, not of the preset");
    }

    @Test
    @Order(4)
    void aSavedFileThatDiffersFromTheSignedPresetIsPutBackWhenTheStoreIsReady() throws Exception {
        JvmSettingsFile.write(image.jvmSettingsFile(), new JvmSettingsFile.Content(JvmPreset.G1_224M, null));
        assertTrue(jvm().path("pending").asBoolean(), "the page says the signed choice is not what the settings file holds");
        sennard.bean(PersonalJvmInstallation.class).reconcile();
        assertEquals(JvmPreset.ZGC_6_PERCENT, JvmSettingsFile.read(image.jvmSettingsFile()).orElseThrow().preset());
        assertTrue(nextStartOptions().contains("-XX:AOTCache=" + image.aotCache(Collector.ZGC)), "the next start is given the signed preset's cache: " + nextStartOptions());
        assertFalse(jvm().path("pending").asBoolean(true));
        assertNull(JvmSettingsFile.read(image.jvmSettingsFile()).orElseThrow().observedBytes(), "the reading the replaced file held is not invented");
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.personal.PersonalPrivateStorage;
import io.github.jaeseongs95.sennard.personal.install.ServerControl;
import io.github.jaeseongs95.sennard.hookclient.ServerAddress;
import io.github.jaeseongs95.sennard.server.admin.AdminPageData.DatabaseLinkView;
import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.context.ContextLayerService.JevKeyView;
import io.github.jaeseongs95.sennard.server.context.PreLinkAdminService;
import io.github.jaeseongs95.sennard.service.database.SqlBuilder;
import io.github.jaeseongs95.sennard.service.personal.LocalFileSealer;
import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;
import io.github.jaeseongs95.sennard.service.secret.SecretSealer;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore;
import io.github.jaeseongs95.sennard.service.store.modules.EncryptedRecords;
import io.github.jaeseongs95.sennard.service.store.pg.DataSourceFactory;
import io.github.jaeseongs95.sennard.service.store.pg.JdbcDrivers;
import io.github.jaeseongs95.sennard.service.store.pg.RemoteConfigProvider;
import io.github.jaeseongs95.sennard.service.store.pg.StoreTransactionConfiguration;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.*;
import io.github.jaeseongs95.sennard.server.skills.TypeSafeProvider.*;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in only. Opens the installation read-only; never boots its server, migrates it or prints credentials. */
@Tag("jev-live")
@SpringJUnitConfig(RegisteredJevPromptTest.Wiring.class)
class RegisteredJevPromptTest {
    @Autowired RegisteredKey keys;
    @Autowired TypeSafeProvider provider;
    @Autowired JsonMapper json;
    @Autowired Settings settings;
    @Autowired ComparisonWire wire;

    @ConfigurationProperties("sennard.test.jev")
    @Validated
    record Settings(@NotNull Path stateDir, @NotNull Path repo,
                    @DefaultValue("0.02") @DecimalMin("0.000001") double maxEstimatedUsd) {}

    // Ignore all page content except connection and verified key metadata; no context bodies or session records are retained.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record InstallationState(DatabaseLinkView link, InstallationSettings settings) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record InstallationSettings(RegisteredJev jev) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegisteredJev(List<JevKeyView> keys) {}

    // Registered explicitly by this opt-in test; unrelated package scans must not discover the live fixture.
    @EnableConfigurationProperties(Settings.class)
    @ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
    @Import({PersonalPrivateStorage.class, JdbcDrivers.class, DataSourceFactory.class, SqlBuilder.class,
            EncryptedRecords.class, ContextLayerStore.class, StoreTransactionConfiguration.class,
            TypeSafeClientConfiguration.class, TypeSafeProvider.class, RegisteredKey.class, ComparisonWire.class})
    static class Wiring {
        // Reuse the running installation's metadata API rather than opening its locked settings database.
        @Bean InstallationState installation(Settings settings) {
            ServerControl control = new ServerControl(settings.stateDir().resolve(ServerAddress.FILE_NAME), Map.of(), Duration.ofSeconds(5));
            String origin = control.adminPage().orElseThrow(() -> new IllegalStateException("Existing installation must be running"));
            URI address = URI.create(origin);
            assertTrue("http".equals(address.getScheme()) && "localhost".equals(address.getHost()) && address.getPort() > 0
                    && address.getUserInfo() == null && address.getQuery() == null && address.getFragment() == null
                    && (address.getPath().isEmpty() || "/".equals(address.getPath())), "The existing admin page must be a localhost origin");
            var http = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
            http.setReadTimeout(Duration.ofSeconds(5));
            InstallationState state = RestClient.builder().requestFactory(http).build()
                    .get().uri(origin + "/api/state").header("Origin", origin).retrieve().body(InstallationState.class);
            return Objects.requireNonNull(state, "Installation metadata is required");
        }

        // Same link, TLS policy, driver and credential-file reader as PersonalStoreConfiguration; no PreparedStore/migration.
        @Bean("store") @Primary
        HikariDataSource store(InstallationState installation, DataSourceFactory pools) {
            assertNotNull(installation.link(), "Existing database link is required");
            assertEquals("READY", installation.link().phase().name(), "The existing store must be ready");
            assertFalse(installation.link().restartRequired(), "The saved link must be the running installation's link");
            var link = Objects.requireNonNull(installation.link().saved(), "Existing database link is required");
            assertFalse(link.jdbcUrl().contains("ags_it_7"), "The shared integration database is excluded");
            var config = new RemoteConfigProvider(link.jdbcUrl(), link.accountName(), Path.of(link.passwordRef()), Path.of(link.driverPath()), 1,
                    PreLinkAdminService.tlsPolicy(link.tls())).provide();
            HikariDataSource pool = DataSourceBuilder.create().type(HikariDataSource.class).build();
            pool.setDataSource(pools.direct(config));
            pool.setMaximumPoolSize(1);
            pool.setMinimumIdle(0);
            pool.setReadOnly(true);
            return pool;
        }

        // Edition constant and JDK resource objects cannot carry component annotations.
        @Bean @Qualifier("storeIsolation") Isolation isolation() { return Isolation.READ_COMMITTED; }
        @Bean SecureRandom random() { return new SecureRandom(); }

        // The same registered-key file and production sealer, opened only; no seal/create call exists in this test.
        @Bean SecretSealer keySealer(Settings settings, SecureRandom random, PrivateStorage storage) {
            return new LocalFileSealer(settings.stateDir().resolve("service/jev-seal.key"), random, storage);
        }

        // RestClient.Builder is a library type. The interceptor is test-only and does not read, log or copy headers.
        @Bean RestClient.Builder restClientBuilder(ComparisonWire wire) {
            return RestClient.builder().requestInterceptor(wire::exchange);
        }
    }

    /** Uses the running service's signed metadata and the existing encrypted repository; no key feature test. */
    static class RegisteredKey {
        private final InstallationState installation;
        private final ContextLayerStore store;

        RegisteredKey(InstallationState installation, ContextLayerStore store) {
            this.installation = installation; this.store = store;
        }

        @Transactional(readOnly = true)
        public SecretValue read() {
            var signed = installation.settings().jev().keys().stream()
                    .filter(key -> TypeSafeProvider.CREDENTIAL_NAME.equals(key.name()) && key.stored()).findFirst()
                    .orElseThrow(() -> new IllegalStateException("A usable signed provider registration is required"));
            assertTrue(Objects.equals(signed.fingerprint(), store.jevKeyFingerprint(TypeSafeProvider.CREDENTIAL_NAME)),
                    "The stored key must match its signed registration");
            var row = store.jevKey(TypeSafeProvider.CREDENTIAL_NAME);
            assertNotNull(row, "The registered key row is required");
            return row.secret();
        }
    }

    /** Only body fields of the frozen baseline are replaced. Candidate traffic stays byte-for-byte as the provider emitted it. */
    static class ComparisonWire {
        private final JsonMapper json;
        private final Settings settings;
        AdvicePrompt baseline;
        long totalBytes;
        int calls;
        ComparisonWire(JsonMapper json, Settings settings) { this.json = json; this.settings = settings; }

        org.springframework.http.client.ClientHttpResponse exchange(org.springframework.http.HttpRequest request, byte[] body,
                org.springframework.http.client.ClientHttpRequestExecution execution) throws IOException {
            assertEquals(TypeSafeProperties.DEFAULT_BASE_URL + TypeSafeProvider.CLASSIFICATION_PATH, request.getURI().toString());
            byte[] sent = body;
            if (baseline != null) {
                ChoiceRequest original = json.readValue(body, ChoiceRequest.class);
                Map<String, Question> questions = new LinkedHashMap<>();
                original.questions().forEach((id, question) -> questions.put(id, new Question(question.type(),
                        new Instructions(baseline.question(), question.instructions().skill()), baseline.options())));
                State state = original.state();
                sent = json.writeValueAsBytes(new ChoiceRequest(original.model(), new State(state.originalPrompt(), state.confirmedContext(),
                        state.taxonomyRevision(), state.classificationCriteriaRef(), baseline.rules()), questions));
            }
            assertTrue(sent.length <= TypeSafeProvider.MAXIMUM_INPUT_BYTES, "Provider input limit");
            assertTrue(calls < 36, "At most 36 requests; no retries");
            long nextBytes = totalBytes + sent.length;
            assertTrue(nextBytes * 0.042 / 1_000_000 <= settings.maxEstimatedUsd(), "Conservative input-cost budget exceeded before send");
            totalBytes = nextBytes;
            calls++;
            return execution.execute(request, sent);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Corpus(Map<String, String> descriptorRefs, Skill syntheticDescriptorDefaults,
                  List<Synthetic> syntheticDescriptors, List<Case> cases) {}
    record Synthetic(String skillId, String description, List<String> actions, List<String> targets,
                     List<String> applicability, List<String> constraints, List<String> exclusions) {
        Skill merge(Skill defaults) {
            return new Skill(skillId, defaults.version(), description, defaults.enabled(), defaults.installed(), defaults.hostSupported(),
                    defaults.capabilities(), actions, targets, constraints == null ? defaults.constraints() : constraints, applicability,
                    exclusions == null ? defaults.exclusions() : exclusions, defaults.dependencies(), defaults.phases(), List.of(), List.of());
        }
    }
    record Availability(boolean enabled, boolean installed, boolean hostSupported) {
        Skill apply(Skill skill) {
            return new Skill(skill.skillId(), skill.version(), skill.description(), enabled, installed, hostSupported, skill.capabilities(),
                    skill.actions(), skill.targets(), skill.constraints(), skill.applicability(), skill.exclusions(), skill.dependencies(),
                    skill.phases(), skill.sourceRefs(), skill.sourceMap());
        }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Case(String id, String originalPrompt, ConfirmedContext confirmedContext, List<String> descriptors,
                Map<String, Availability> descriptorOverrides, Map<String, Verdict> expected) {}
    record Observation(String caseId, String arm, String actualPromptRevision, Map<String, Verdict> expected, JevProvider.Evaluated result) {}
    record Report(String model, String candidateRevision, String corpusDigest, int cases, int judgmentsPerArm,
                  int calls, long sentBytes, List<Observation> observations) {}

    @Test @Tag("jev-live-comparison")
    void compareTheFrozenCorpusThroughTheProductionProvider() throws IOException {
        Path repo = settings.repo();
        byte[] corpusBytes = Files.readAllBytes(repo.resolve("tests/jev-prompt/cases.json"));
        Corpus corpus = json.readValue(corpusBytes, Corpus.class);
        assertEquals(18, corpus.cases().size());
        assertEquals(18, corpus.cases().stream().map(Case::id).distinct().count());
        int judgments = corpus.cases().stream().mapToInt(c -> c.expected().size()).sum();
        assertEquals(32, judgments);
        var properties = new SennardProperties(null, null, repo, repo, false, null, null, null, null, null, null, null);
        var inventory = new SkillInventoryReader(properties, json).observed().inventory();
        assertTrue(inventory.issues().isEmpty(), "Production descriptor loader must accept every descriptor");
        Map<String, Skill> descriptors = new LinkedHashMap<>();
        inventory.skills().forEach(skill -> descriptors.put(skill.skillId(), skill));
        corpus.syntheticDescriptors().forEach(skill -> descriptors.put(skill.skillId(), skill.merge(corpus.syntheticDescriptorDefaults())));
        AdvicePrompt baseline = TypeSafeProviderAdvicePromptTest.baseline();
        assertEquals("r01", baseline.revision());
        var profile = provider.profileFor("jev-1.14.0").orElseThrow();
        assertEquals(0.042, provider.estimateUsd(1_000_000), 1e-12, "The wire budget must track the production input price");
        SecretValue key = keys.read(); // The read-only transaction ends before any network I/O.
        List<Observation> observations = new ArrayList<>();
        Path reports = Path.of("build/reports/jev-registered-key");
        Files.createDirectories(reports);
        Path output = Files.createTempDirectory(reports, "run-").resolve("results.json");
        try {
            for (int index = 0; index < corpus.cases().size(); index++) {
                Case c = corpus.cases().get(index);
                List<Skill> skills = new ArrayList<>();
                for (String ref : c.descriptors()) {
                    String id = corpus.descriptorRefs().getOrDefault(ref, ref);
                    Skill skill = Objects.requireNonNull(descriptors.get(id), "Every corpus descriptor must be available");
                    if (c.descriptorOverrides() != null && c.descriptorOverrides().containsKey(id)) skill = c.descriptorOverrides().get(id).apply(skill);
                    skills.add(skill);
                }
                assertEquals(c.expected().keySet(), skills.stream().map(Skill::skillId).collect(java.util.stream.Collectors.toSet()));
                ConfirmedContext context = c.confirmedContext() == null ? new ConfirmedContext(null, null, null, null, null, null, null) : c.confirmedContext();
                Request request = new Request("1.0.0", c.id(), c.id(), c.originalPrompt(), Checks.digest(json, c.originalPrompt()), context,
                        List.of(), skills, Checks.digest(json, skills), inventory.taxonomyRevision(), "jev-development-corpus", Checks.digest(json, c));
                for (boolean old : index % 2 == 0 ? List.of(true, false) : List.of(false, true)) {
                    wire.baseline = old ? baseline : null;
                    String actualRevision = old ? profile.adapterRevision() + ":" + Checks.digest(json, baseline) : profile.promptRevision();
                    observations.add(new Observation(c.id(), old ? "r01" : "candidate", actualRevision, c.expected(), provider.classify(request, profile, key)));
                    Files.write(output, json.writeValueAsBytes(new Report(profile.modelId(), profile.promptRevision(), "sha256:" + Digests.sha256Hex(corpusBytes),
                            corpus.cases().size(), judgments, wire.calls, wire.totalBytes, List.copyOf(observations))));
                }
            }
        } finally {
            wire.baseline = null;
        }
        assertEquals(36, observations.size());
        assertTrue(observations.stream().allMatch(o -> o.result() instanceof JevProvider.Evaluation), "Every response must satisfy the production contract; failures stay in results.json");
        Map<String, Verdict> previous = new LinkedHashMap<>();
        int oldCorrect = 0;
        int newCorrect = 0;
        for (Observation o : observations) {
            var result = (JevProvider.Evaluation) o.result();
            for (Judgment j : result.response().judgments()) {
                String id = o.caseId() + ":" + j.skillId();
                Verdict expected = o.expected().get(j.skillId());
                if ("r01".equals(o.arm())) { previous.put(id, j.judgment()); if (expected == j.judgment()) oldCorrect++; }
                else if (expected == j.judgment()) newCorrect++;
            }
        }
        assertTrue(newCorrect >= oldCorrect, "Candidate must not reduce correct choices on the frozen development corpus");
        for (Observation o : observations) {
            if (!"candidate".equals(o.arm())) continue;
            for (Judgment j : ((JevProvider.Evaluation) o.result()).response().judgments()) {
                if (o.expected().get(j.skillId()) == Verdict.NEEDED && previous.get(o.caseId() + ":" + j.skillId()) == Verdict.NEEDED)
                    assertEquals(Verdict.NEEDED, j.judgment(), "Candidate newly missed a required skill in " + o.caseId());
            }
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.MessageSource;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.util.IdGenerator;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.LocaleResolver;

import io.github.jaeseongs95.sennard.app.message.CatalogMessageSource;
import io.github.jaeseongs95.sennard.app.support.ErrorTranslationAdvisor;
import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.skills.SkillCheck;
import io.github.jaeseongs95.sennard.server.web.SennardLocaleResolver;
import io.github.jaeseongs95.sennard.server.workflow.ContentRoot;
import io.github.jaeseongs95.sennard.server.workflow.HostIdentity;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkSettings;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistry;
import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminRecords;
import tools.jackson.databind.json.JsonMapper;

/**
 * The classes of ours that the application finds by component scan, and the beans a {@code @Bean} method still makes, in the assembled application.
 *
 * <pre>
 * s1  the message source             one bean named messageSource, a CatalogMessageSource, the only MessageSource
 * s2  the locale resolver            one bean named localeResolver (the name DispatcherServlet looks for), an SennardLocaleResolver, the only LocaleResolver
 * s3  the error translation          one bean named errorTranslationAdvisor, an infrastructure bean
 * s4  the workflow's roots           one ContentRoot (the settings' content folder) and one HostIdentity (its file is made in the state folder)
 * s6  the skill checks               each of coordinate-subagents, test-engineering, code-review, prompt-engineering and solid-design exactly once
 * s7  the factory-made beans         clock (UTC), idGenerator, messageSourceAccessor and the secure random exist; the second JSON mappers are not default candidates
 * s9  the settings store             one PasskeyRegistry and PreLinkAdminRecords; the H2 file is under the state folder; its DataSource and transaction manager are other beans than the business ones
 * s8  the stateless endpoint        the stateless transport and no streamable tool list; a start given the STREAMABLE protocol is refused, so those beans never exist
 * </pre>
 */
class ComponentRegistrationServerTest {
    private static RunningServer server;

    @BeforeAll
    static void start() throws Exception {
        server = RunningServer.start(List.of(ServerFixtures.Beans.class));
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void s1TheMessageSourceIsTheCatalogOneUnderTheNameTheContextLooksFor() {
        assertInstanceOf(CatalogMessageSource.class, server.context.getBean("messageSource"));
        assertEquals(1, server.context.getBeansOfType(CatalogMessageSource.class).size());
        assertEquals(1, server.context.getBeansOfType(MessageSource.class).size(), "the catalog source is the only one");
    }

    @Test
    void s2TheLocaleResolverIsOursUnderTheNameDispatcherServletLooksFor() {
        assertInstanceOf(SennardLocaleResolver.class, server.context.getBean(DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME));
        assertEquals(1, server.context.getBeansOfType(LocaleResolver.class).size(), "Boot's default resolver backs off for it");
    }

    @Test
    void s3TheErrorTranslationIsOneInfrastructureBean() {
        assertEquals(1, server.context.getBeansOfType(ErrorTranslationAdvisor.class).size());
        assertInstanceOf(ErrorTranslationAdvisor.class, server.context.getBean("errorTranslationAdvisor"));
        assertEquals(BeanDefinition.ROLE_INFRASTRUCTURE, server.context.getBeanFactory().getBeanDefinition("errorTranslationAdvisor").getRole());
    }

    @Test
    void s4TheContentRootAndTheHostIdentityAreTheSettingsOnes() {
        assertEquals(1, server.context.getBeansOfType(ContentRoot.class).size());
        assertEquals(server.bean(SennardProperties.class).content().toAbsolutePath().normalize(), server.bean(ContentRoot.class).path());
        assertEquals(1, server.context.getBeansOfType(HostIdentity.class).size());
        String id = server.bean(HostIdentity.class).id();
        assertFalse(id.isBlank());
        assertTrue(Files.exists(server.stateDirectory().resolve(HostIdentity.FILE)), "the identity is kept in the state folder");
        assertEquals(id, server.bean(HostIdentity.class).id(), "the same identity at every ask");
    }

    @Test
    void s6EachSkillCheckExistsExactlyOnce() {
        List<String> skills = server.context.getBeansOfType(SkillCheck.class).values().stream().map(check -> ((SkillCheck<?>) check).skill()).collect(Collectors.toList());
        for (String skill : List.of("coordinate-subagents", "test-engineering", "code-review", "prompt-engineering", "solid-design")) {
            assertEquals(1, skills.stream().filter(skill::equals).count(), skill + " in " + skills);
        }
    }

    @Test
    void s7TheFactoryMadeBeansStillExist() {
        assertEquals(ZoneOffset.UTC, server.context.getBean("clock", Clock.class).getZone());
        assertEquals(1, server.context.getBeansOfType(IdGenerator.class).size());
        assertEquals(1, server.context.getBeansOfType(MessageSourceAccessor.class).size());
        assertTrue(server.context.containsBean("secureRandom"));
        Map<String, JsonMapper> mappers = server.context.getBeansOfType(JsonMapper.class);
        assertTrue(mappers.containsKey("mcpServerJsonMapper") && mappers.containsKey("sortedJsonMapper"), mappers.keySet().toString());
        JsonMapper application = server.bean(JsonMapper.class);
        assertNotSame(mappers.get("mcpServerJsonMapper"), application, "the second mappers are not default candidates");
        assertNotSame(mappers.get("sortedJsonMapper"), application, "the second mappers are not default candidates");
    }

    @Test
    void s9TheSettingsStoreIsAnOwnDatabaseUnderTheStateFolderApartFromTheBusinessBeans() {
        assertEquals(1, server.context.getBeansOfType(PasskeyRegistry.class).size());
        assertEquals(1, server.context.getBeansOfType(PreLinkAdminRecords.class).size());
        assertEquals(1, server.context.getBeansOfType(DatabaseLinkSettings.class).size(), "the link the admin page saves is the settings database's too");
        assertTrue(Files.exists(server.stateDirectory().resolve("settings").resolve("sennard-settings.mv.db")), "the settings database is a file under the state folder");
        assertNotSame(server.context.getBean("store"), server.context.getBean("settingsDataSource"), "the settings DataSource is not the business one");
        assertNotSame(server.context.getBean("transactionManager"), server.context.getBean("settingsTransactionManager"));
        // The registry answers from the file in the state folder (nothing is registered in a fresh state folder).
        assertTrue(server.bean(PasskeyRegistry.class).list().isEmpty());
    }

    @Test
    void s8TheMcpEndpointIsTheStatelessOneAndNoSettingChangesThat() {
        assertTrue(server.context.containsBean("webMvcStatelessServerTransport"), "stateless is the only protocol");
        assertFalse(server.context.containsBean("sessionBoardToolsStreamable"));
        assertFalse(server.context.containsBean("sessionMessageToolsStreamable"));
        assertFalse(server.context.containsBean("promptToolsStreamable"));
        assertFalse(server.context.containsBean("codexHookSpecificationStreamable"));
        // A start given the streamable protocol does not come up with a second tool list: it is refused, and no context with those beans exists.
        Exception refused = assertThrows(Exception.class, () -> RunningServer.start(List.of(ServerFixtures.Beans.class), "--spring.ai.mcp.server.protocol=STREAMABLE"));
        assertTrue(NestedExceptionUtils.getMostSpecificCause(refused).getMessage().contains("spring.ai.mcp.server.protocol must be STATELESS"), refused.toString());
    }
}

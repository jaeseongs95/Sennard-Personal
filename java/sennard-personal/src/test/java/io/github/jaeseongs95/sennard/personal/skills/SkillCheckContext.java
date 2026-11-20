// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.skills;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import io.github.jaeseongs95.sennard.app.message.CatalogMessageSource;
import io.github.jaeseongs95.sennard.personal.LocalFileFacts;
import io.github.jaeseongs95.sennard.personal.BatchFileArguments;
import io.github.jaeseongs95.sennard.personal.LocalProcessRunner;
import io.github.jaeseongs95.sennard.personal.ProgramLocator;
import io.github.jaeseongs95.sennard.personal.RunEnvironment;
import io.github.jaeseongs95.sennard.personal.PersonalProperties;
import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.limits.ResourceLimitSource;
import io.github.jaeseongs95.sennard.server.limits.ResourceLimits;
import io.github.jaeseongs95.sennard.server.message.Messages;
import tools.jackson.databind.json.JsonMapper;

/**
 * The skills' checks, their real file boundary and nothing else of that package: the classification and host discovery beans need the store and a running host, and the MCP tool (SkillCheckTool) reads the job store, so the tests reach the checks through SkillCheckService, the tool's own entry.
 * ChangeScopeCheck reads the run's change record in the business database, which this context does not have; its cases run on the running server (ChangeScopeServerTest).
 */
public final class SkillCheckContext {
    private static final Pattern NOT_A_CHECK = Pattern.compile("io\\.github\\.jaeseongs95\\.sennard\\.server\\.skills\\.(ChangeScopeCheck|JevAdviceHook|JevBudget|JevProvider|JevProviders|JevReadiness|JevRetry|TypeSafeProvider|TypeSafeClientConfiguration|SkillClassificationService|SkillInventoryReader|SkillTools|SkillCheckTool|SkillCheckQueue)");

    private SkillCheckContext() {
    }

    /**
     * The checks with the real process runner, for a test that does not run a program: the runner's work folder lies under a state directory that is made here and
     * removed when the test JVM ends (nothing is written to it unless a run starts).
     */
    public static AnnotationConfigApplicationContext open() {
        try {
            Path state = Files.createTempDirectory("skill-check-state");
            state.toFile().deleteOnExit();
            return open(state);
        } catch (IOException notMade) {
            throw new UncheckedIOException("The state directory of the skill checks could not be made.", notMade);
        }
    }

    /** The checks as the personal edition has them: with the real process runner, which keeps its work under {@code stateDirectory}. */
    public static AnnotationConfigApplicationContext open(Path stateDirectory) {
        return open(stateDirectory, Map.of());
    }

    /** As {@link #open(Path)} with these {@code sennard.*} settings bound the way Boot binds them (for example {@code sennard.run.max-concurrent}). */
    public static AnnotationConfigApplicationContext open(Path stateDirectory, Map<String, String> settings) {
        return open(stateDirectory, settings, () -> ResourceLimits.DEFAULT);
    }

    static AnnotationConfigApplicationContext open(Path stateDirectory, Map<String, String> settings, ResourceLimitSource resourceLimits) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(context);
        scanner.addExcludeFilter(new RegexPatternTypeFilter(NOT_A_CHECK));
        scanner.scan("io.github.jaeseongs95.sennard.server.skills");
        context.register(LocalFileFacts.class, Messages.class);
        context.registerBean(ResourceLimitSource.class, () -> resourceLimits);
        context.registerBean("personalProperties", PersonalProperties.class, () -> new PersonalProperties(stateDirectory, Path.of("."), new PersonalProperties.Store(false, org.springframework.transaction.annotation.Isolation.READ_COMMITTED)));
        context.registerBean("sennardProperties", SennardProperties.class, () -> new Binder(new MapConfigurationPropertySource(settings)).bindOrCreate("sennard", SennardProperties.class));
        context.register(LocalProcessRunner.class, RunEnvironment.class, BatchFileArguments.class, ProgramLocator.class);
        context.registerBean("clock", java.time.Clock.class, java.time.Clock::systemUTC); // the application's one Clock bean (AppConfiguration in the product)
        // The wording the checks speak: the same message source the application has, over the same catalogs.
        context.registerBean("jsonMapper", JsonMapper.class, () -> TestJson.MAPPER); // the application's JsonMapper bean (Boot's auto-configuration in the product)
        context.registerBean("messageSource", MessageSource.class, () -> new CatalogMessageSource(context, context.getBean(JsonMapper.class)));
        context.registerBean(MessageSourceAccessor.class, () -> new MessageSourceAccessor(context.getBean(MessageSource.class), Locale.ENGLISH));
        context.refresh();
        return context;
    }
}

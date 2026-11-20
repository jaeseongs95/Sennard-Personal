// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;

/**
 * The settings that used to be read by hand ({@code System.getenv}, {@code user.home}, a placeholder with a default in an annotation), now bound to records from the product's
 * own {@code application.yaml}, the way the server binds them (with validation, as {@code @Validated} makes the context do). The environment and the system properties are
 * given to Spring's own environment, so the cases do not depend on the PC the test runs on. Cases:
 *
 * <pre>
 * h1  Claude folder, variable set        CLAUDE_CONFIG_DIR names the folder; the note roots are under it
 * h2  Claude folder, variable not set    the .claude folder of the user's home
 * h2b the variable is empty              the same (POSIX can have an empty variable; the old code took it as not set)
 * h2c the variable is only white space   the same
 * h3  Gemini folder                      the .gemini folder of the user's home (Antigravity's note roots are under it)
 * h4  update interval                    not set: five minutes (the old 300000 ms); a plain number is milliseconds; a unit is understood
 * h6  no hosts settings at all           the binding is refused by validation (the server does not start), naming the setting
 * </pre>
 */
class HostFoldersTest {
    private static final String HOME = Path.of("home", "somebody").toAbsolutePath().toString();

    /** The binder of an environment made of these variables and the product's application.yaml, with {@code user.home} set to {@link #HOME}. */
    private static Binder binder(Map<String, Object> variables, Map<String, Object> settings) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of("user.home", HOME)));
        environment.getPropertySources().addFirst(new MapPropertySource("settings", settings));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")).forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment);
    }

    private static ValidationBindHandler validation() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return new ValidationBindHandler(validator);
    }

    /** The server's settings from the product's application.yaml and these variables, validated. */
    private static SennardProperties properties(Map<String, Object> variables) throws IOException {
        return binder(variables, Map.of()).bindOrCreate("sennard", Bindable.of(SennardProperties.class), validation());
    }

    private static List<String> claudeNoteRoots(Map<String, Object> variables) throws IOException {
        return new ClaudeWorkFacts(properties(variables), new MsysShellPaths()).noteRoots();
    }

    @Test
    void h1TheVariableNamesClaudesFolderAndTheNoteRootsAreUnderIt() throws IOException {
        Path moved = Path.of("elsewhere", "claude").toAbsolutePath();
        Map<String, Object> variables = Map.of("CLAUDE_CONFIG_DIR", moved.toString());
        assertEquals(moved, properties(variables).hosts().claudeConfig());
        assertEquals(List.of(moved.resolve("projects") + "/*/memory"), claudeNoteRoots(variables));
    }

    @Test
    void h2WithoutTheVariableClaudesFolderIsTheDotClaudeOfTheHome() throws IOException {
        assertEquals(Path.of(HOME, ".claude"), properties(Map.of()).hosts().claudeConfig());
        assertEquals(List.of(Path.of(HOME, ".claude").resolve("projects") + "/*/memory"), claudeNoteRoots(Map.of()));
    }

    @Test
    void h2bAnEmptyVariableIsTakenAsNotSet() throws IOException {
        Map<String, Object> variables = Map.of("CLAUDE_CONFIG_DIR", "");
        assertEquals(Path.of(HOME, ".claude"), properties(variables).hosts().claudeConfig(), "not the current folder");
        assertEquals(List.of(Path.of(HOME, ".claude").resolve("projects") + "/*/memory"), claudeNoteRoots(variables));
    }

    @Test
    void h2cAVariableOfOnlyWhiteSpaceIsTakenAsNotSet() throws IOException {
        Map<String, Object> variables = Map.of("CLAUDE_CONFIG_DIR", "   ");
        assertEquals(Path.of(HOME, ".claude"), properties(variables).hosts().claudeConfig());
        assertEquals(List.of(Path.of(HOME, ".claude").resolve("projects") + "/*/memory"), claudeNoteRoots(variables));
    }

    @Test
    void h3GeminisFolderIsTheDotGeminiOfTheHome() throws IOException {
        Path gemini = Path.of(HOME, ".gemini");
        assertEquals(gemini, properties(Map.of()).hosts().geminiConfig());
        assertEquals(List.of(gemini.resolve("antigravity-cli").resolve("brain").toString(), gemini.resolve("antigravity").resolve("brain").toString()),
                new AntigravityWorkFacts(new SkillFileReads(""), properties(Map.of()), new MsysShellPaths()).noteRoots());
    }

    @Test
    void h4TheUpdateIntervalIsFiveMinutesAndTakesANumberOrAUnit() throws IOException {
        assertEquals(Duration.ofMillis(300_000), binder(Map.of(), Map.of()).bindOrCreate("sennard", SennardProperties.class).updates().pollInterval());
        assertEquals(Duration.ofSeconds(60), binder(Map.of(), Map.of("sennard.updates.poll-interval", "60000")).bindOrCreate("sennard", SennardProperties.class).updates().pollInterval(), "a plain number is ms");
        assertEquals(Duration.ofSeconds(30), binder(Map.of(), Map.of("sennard.updates.poll-interval", "30s")).bindOrCreate("sennard", SennardProperties.class).updates().pollInterval());
    }

    @Test
    void h6WithoutTheHostsSettingsTheBindingIsRefused() {
        Binder bare = new Binder(new MapConfigurationPropertySource(Map.of("sennard.updates.poll-interval", "5m")));
        BindException refused = assertThrows(BindException.class, () -> bare.bindOrCreate("sennard", Bindable.of(SennardProperties.class), validation()));
        assertTrue(refused.getCause().getMessage().contains("hosts.claudeConfigDir") && refused.getCause().getMessage().contains("hosts.userHome"), refused.getCause().getMessage());
    }
}

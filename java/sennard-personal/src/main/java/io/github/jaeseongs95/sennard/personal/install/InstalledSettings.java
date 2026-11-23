// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.List;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

import io.github.jaeseongs95.sennard.app.RandomConfiguration;
import io.github.jaeseongs95.sennard.personal.PersonalProperties;
import io.github.jaeseongs95.sennard.hookclient.ServerAddress;
import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.hook.ClientHookRegistration;
import io.github.jaeseongs95.sennard.server.hook.HostDefinitions;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the commands need from an installation, read the way the server reads it: {@code application.yaml}, the environment and that
 * installation's {@code sennard.yaml}. The installer needs the host registration definitions and package locations.
 *
 * <p>The values are injected, not looked up: the context hands them to this object when it has started ({@link Beans#deliverSettings}), and the object keeps only the values and
 * the context it closes. It asks the context for no bean, so it is built the way every other class is, from what it is given.
 */
public final class InstalledSettings implements AutoCloseable {
    // Not a @Configuration: the server's own scan would find it and run this scan again inside the server's context.
    @EnableConfigurationProperties({PersonalProperties.class, SennardProperties.class})
    @ImportAutoConfiguration(JacksonAutoConfiguration.class)
    @Import(RandomConfiguration.class) // the one SecureRandom of the application, for the reset command that opens the store itself
    @ComponentScan(basePackageClasses = HostPackageLocation.class)
    static class Beans {
        /**
         * Runs when the context has started and hands the injected parts to the {@link Delivery} the start registered. A runner and not a bean the caller asks for, so that no
         * code needs the context to find anything in it. (A {@code @Bean} method of a class that is not a {@code @Configuration} is a "lite" one, which this class's
         * {@code @ComponentScan} and {@code @Import} make it.)
         */
        // @Bean: a lambda of Spring Boot's ApplicationRunner (a library type), made from the beans this context was given.
        @Bean
        ApplicationRunner deliverSettings(PersonalProperties personal, SecureRandom random, JsonMapper json,
                List<HostPackageLocation> locations, Delivery delivery) {
            List<ClientHookRegistration> adapters = HostDefinitions.load().all().stream()
                    .<ClientHookRegistration>map(host -> host.hookAdapter(json)).toList();
            return arguments -> delivery.deliver(new Parts(personal, random, json, adapters, locations));
        }
    }

    /** What the context gives this object. */
    private record Parts(PersonalProperties personal, SecureRandom random, JsonMapper json, List<ClientHookRegistration> adapters,
            List<HostPackageLocation> locations) {
    }

    /** The place the runner leaves the parts: registered in the context before it starts, taken by the caller of the start after it. */
    static final class Delivery {
        private Parts parts;

        void deliver(Parts delivered) {
            this.parts = delivered;
        }

        private Parts delivered() {
            if (parts == null) {
                throw new IllegalStateException("The settings context started without delivering its settings.");
            }
            return parts;
        }
    }

    private final ConfigurableApplicationContext context;
    private final Parts parts;

    private InstalledSettings(ConfigurableApplicationContext context, Parts parts) {
        this.context = context;
        this.parts = parts;
    }

    /** @param appDirectory the directory holding the jar and {@code sennard.yaml} of the installation to read */
    public static InstalledSettings at(Path appDirectory) {
        return start("sennard.home=" + appDirectory);
    }

    /**
     * The same read for a process whose standard output is a host's hook answer: Boot's console logging writes to the standard output, and a line of it would be
     * taken for the answer. A failure is not lost: it reaches the caller as the exception the context's start throws.
     */
    public static InstalledSettings quietlyAt(Path appDirectory) {
        return start("sennard.home=" + appDirectory, "logging.level.root=OFF");
    }

    private static InstalledSettings start(String... properties) {
        Delivery delivery = new Delivery();
        ConfigurableApplicationContext context = new SpringApplicationBuilder(Beans.class).web(WebApplicationType.NONE)
                .initializers(starting -> starting.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("settingsDelivery", delivery)))
                .properties(properties).logStartupInfo(false).run("--spring.main.keep-alive=false");
        return new InstalledSettings(context, delivery.delivered());
    }

    public Path stateDirectory() {
        return parts.personal().stateDir();
    }

    /**
     * The file the running server writes its address to ({@link ServerAddress#FILE_NAME}) in the state folder: the server's port is the operating system's choice at each start, so a
     * command finds the server through this file and not through a setting.
     */
    public Path serverAddressFile() {
        return stateDirectory().resolve(ServerAddress.FILE_NAME);
    }

    /** False only for an installation told not to prepare its store. */
    public boolean preparesStore() {
        return parts.personal().store().prepare();
    }

    public SecureRandom secureRandom() {
        return parts.random();
    }

    /** The context's one JSON mapper (Boot's): the commands that read or write JSON files of an installation take it from here and make none of their own. */
    public JsonMapper jsonMapper() {
        return parts.json();
    }

    public Collection<ClientHookRegistration> hostAdapters() {
        return parts.adapters();
    }

    public Collection<HostPackageLocation> hostLocations() {
        return parts.locations();
    }

    @Override
    public void close() {
        context.close();
    }
}

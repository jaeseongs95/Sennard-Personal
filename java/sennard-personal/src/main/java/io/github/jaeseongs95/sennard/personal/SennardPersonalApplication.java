// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.autoconfigure.web.DataWebAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;

import io.github.jaeseongs95.sennard.protocol.Utf8Boundary;
import io.github.jaeseongs95.sennard.service.personal.LocalPrivateStorage;
import tools.jackson.core.ErrorReportConfiguration;

/**
 * The personal edition's server: one Spring Boot web application that is the MCP server. It takes no command line; the OS
 * starts it with no arguments and a path that does not fit the Windows ANSI code page would not survive one, so settings
 * come from the environment and from {@code sennard.yaml} beside the jar.
 */
// The server's modules each hold a class with the same short name (three Messages), so component names are the qualified names.
// DataWebAutoConfiguration is excluded: it enables Spring Data's web support, whose DomainClassConverter asks every repository when a request parameter is converted (it builds
// Spring Data's Repositories over the context), and the repositories are made at their first use, after the business database is ready. No endpoint binds a domain class, a Pageable or a Sort.
// DataSourceInitializationAutoConfiguration is excluded: it runs Boot's SQL initialization (schema.sql, data.sql, spring.sql.init), which Sennard does not use (the migrations have their own runner), and it makes every JdbcClient depend on the data source's initializer, so the settings store's JdbcClient that the data source itself needs (store -> preparedStore -> settings) becomes a circle.
@SpringBootApplication(scanBasePackages = {"io.github.jaeseongs95.sennard.server", "io.github.jaeseongs95.sennard.personal", "io.github.jaeseongs95.sennard.app"}, nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class,
        exclude = {DataWebAutoConfiguration.class, DataSourceInitializationAutoConfiguration.class})
@EnableConfigurationProperties(PersonalProperties.class)
public class SennardPersonalApplication {
    public static void main(String[] args) {
        Utf8Boundary.fixStandardStreams();
        // A JSON parse error names the token it stopped at, and the libraries in front of the endpoints log that message (Spring MVC's
        // resolver, Tomcat for an uncaught one): a request body must not reach the log file this way. Every parser made from here on
        // reports the kind of error and its position without the input's text.
        ErrorReportConfiguration.overrideDefaultErrorReportConfiguration(ErrorReportConfiguration.builder().maxErrorTokenLength(0).maxRawContentLength(0).build());
        SpringApplication application = new SpringApplication(SennardPersonalApplication.class);
        // Spring Boot starts headless; the tray icon (ServerTray) needs AWT's desktop side. Whether there is a desktop to show it on is the JDK's answer (TrayAvailability), so a server on a machine
        // without one runs as before. Boot keeps a java.awt.headless that is already set (-Djava.awt.headless=true), and the tests, which do not start through here, stay headless.
        application.setHeadless(false);
        // The state folder holds the log file: it is made through the port of the storage of secrets before the logging system makes it with the default access.
        application.addListeners(new StateFolderBeforeLogging(new LocalPrivateStorage()));
        application.setDefaultProperties(Map.of("sennard.home", new ApplicationHome(SennardPersonalApplication.class).getDir().getPath(),
                // Only this process: the OS starts it without a console, so a failed start would otherwise leave nothing to read. The commands read the same application.yaml and must not share the file.
                "logging.file.name", "${sennard.state-dir}/logs/sennard-server.log",
                "logging.charset.file", "UTF-8",
                "logging.logback.rollingpolicy.max-file-size", "10MB",
                "logging.logback.rollingpolicy.max-history", "7",
                "logging.logback.rollingpolicy.total-size-cap", "100MB"));
        try {
            application.run();
        } catch (SpringApplication.AbandonedRunException trainingRun) {
            throw trainingRun; // the AOT training ends the run on purpose
        } catch (RuntimeException startFailed) {
            // Boundary (process entry, {@code main}): the one place a failed start becomes the process's exit. Boot has logged it with its failure analysis. Returning a failure to the Windows launcher without a console would open a dialog and keep
            // the process until someone closes it, which nobody can in a logon task.
            // The logger is made here, not in a static field: Boot has set its logging up by now, and a field would make Logback before Boot configures it.
            Logger log = LoggerFactory.getLogger(SennardPersonalApplication.class);
            log.debug("The server did not start; the process ends with exit code 1.", startFailed);
            System.exit(1);
        }
    }
}

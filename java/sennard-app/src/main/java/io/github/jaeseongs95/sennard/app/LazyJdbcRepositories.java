// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app;

import java.lang.annotation.Annotation;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.data.jdbc.repository.config.JdbcRepositoryConfigExtension;
import org.springframework.data.repository.config.AnnotationRepositoryConfigurationSource;
import org.springframework.data.repository.config.BootstrapMode;
import org.springframework.data.repository.config.RepositoryBeanDefinitionRegistrarSupport;
import org.springframework.data.repository.config.RepositoryConfigurationDelegate;
import org.springframework.data.repository.config.RepositoryConfigurationExtension;
import org.springframework.data.repository.config.RepositoryConfigurationUtils;

import io.github.jaeseongs95.sennard.app.attachment.UploadStagingRepository;
import io.github.jaeseongs95.sennard.app.board.BoardSessionRepository;
import io.github.jaeseongs95.sennard.app.journal.JournalWorkflowRepository;
import io.github.jaeseongs95.sennard.app.messaging.MessageRepository;
import io.github.jaeseongs95.sennard.app.operation.OperationReceiptRepository;
import io.github.jaeseongs95.sennard.app.prompt.UserPromptRepository;

/**
 * Registers the repositories of the rewritten areas with {@link BootstrapMode#LAZY}: a repository is made when it is first used, and what the injection points
 * of the services get until then is Spring Data's lazy-resolution proxy. {@code @EnableJdbcRepositories} has no {@code bootstrapMode} attribute (the JPA and MongoDB
 * annotations have) and Spring Data's registrar reads the mode only from that attribute, so this is Spring Data's registrar for Data JDBC
 * ({@link RepositoryBeanDefinitionRegistrarSupport}) with one change: its configuration source answers LAZY. Everything else is the registrar's own
 * steps, among them exposing the extension as a bean, which is what makes Boot's {@code DataJdbcRepositoriesAutoConfiguration} step aside.
 *
 * <p>Why lazy: a repository needs the Spring Data JDBC dialect ({@code @Query} methods ask it for the identifier quoting while the repository is made), and the
 * dialect of a business database is asked from its connection (MySQL and MariaDB quote as the server's {@code ANSI_QUOTES} says), which does not exist while the server
 * starts: the database is prepared behind the listener. The repository's first use is inside a service's transaction, which has a connection.
 */
final class LazyJdbcRepositories extends RepositoryBeanDefinitionRegistrarSupport {
    private ResourceLoader resourceLoader;
    private Environment environment;

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        super.setResourceLoader(resourceLoader);
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void setEnvironment(Environment environment) {
        super.setEnvironment(environment);
        this.environment = environment;
    }

    @Override
    protected Class<? extends Annotation> getAnnotation() {
        return EnableJdbcRepositories.class;
    }

    @Override
    protected RepositoryConfigurationExtension getExtension() {
        return new JdbcRepositoryConfigExtension();
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry, BeanNameGenerator importBeanNameGenerator) {
        AnnotationRepositoryConfigurationSource source = new AnnotationRepositoryConfigurationSource(AnnotationMetadata.introspect(RepositoryPackages.class),
                getAnnotation(), resourceLoader, environment, registry, importBeanNameGenerator) {
            @Override
            public BootstrapMode getBootstrapMode() {
                return BootstrapMode.LAZY;
            }
        };
        RepositoryConfigurationExtension extension = getExtension();
        RepositoryConfigurationUtils.exposeRegistration(extension, registry, source);
        new RepositoryConfigurationDelegate(source, resourceLoader, environment).registerRepositoriesIn(registry, extension);
    }

    /** What Spring Data reads the {@code @EnableJdbcRepositories} attributes from: the packages of the rewritten areas (one repository of each names its package); it is never a bean. */
    @EnableJdbcRepositories(basePackageClasses = {BoardSessionRepository.class, MessageRepository.class, UserPromptRepository.class, JournalWorkflowRepository.class,
            OperationReceiptRepository.class, UploadStagingRepository.class})
    private static final class RepositoryPackages {
    }
}

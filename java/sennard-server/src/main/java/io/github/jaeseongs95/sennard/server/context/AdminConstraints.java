// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashSet;
import java.util.ServiceLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.jaeseongs95.sennard.server.hook.ShellPaths;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * The rules of the signed admin records that Jakarta Validation has no ready-made constraint for. Each answers true for null: a value that must be
 * present says so with {@code @NotNull}, as the standard constraints do.
 */
public final class AdminConstraints {
    private static final Logger LOG = LoggerFactory.getLogger(AdminConstraints.class);

    private AdminConstraints() {
    }

    /** A role name exactly as {@link AdminLog#normalizeRoleName} writes it: NFC, trimmed, one line of at most 100 characters. */
    @Documented
    @Constraint(validatedBy = RoleNameValidator.class)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE, ElementType.ANNOTATION_TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface RoleName {
        String message() default "must be a role name in its normalized form";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static final class RoleNameValidator implements ConstraintValidator<RoleName, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.equals(AdminLog.normalizeRoleName(value));
        }
    }

    /** The name of one of the chains of the log (see {@link AdminLog#isTarget}). */
    @Documented
    @Constraint(validatedBy = ChainValidator.class)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE, ElementType.ANNOTATION_TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Chain {
        String message() default "must name a chain of the admin log";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static final class ChainValidator implements ConstraintValidator<Chain, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || AdminLog.isTarget(value);
        }
    }

    /** An absolute path, or a well-formed glob pattern, with no white space at either end and no control character (see {@link AdminLog#exemptEntry}). */
    @Documented
    @Constraint(validatedBy = ExemptPathValidator.class)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE, ElementType.ANNOTATION_TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ExemptPath {
        String message() default "must be an absolute path or a glob pattern without surrounding white space or control characters";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static final class ExemptPathValidator implements ConstraintValidator<ExemptPath, String> {
        /**
         * Hibernate Validator makes this validator with a no-argument constructor, with Spring's constraint validator factory or, in the admin reset command that
         * folds a log without a container, on its own; so the shell's path spelling is found the way the JDK finds implementations without one, the same
         * implementation the Spring context holds as a bean.
         */
        private final ShellPaths shell = ServiceLoader.load(ShellPaths.class, ShellPaths.class.getClassLoader()).findFirst()
                .orElseThrow(() -> new IllegalStateException("No implementation of " + ShellPaths.class.getName() + " is named in META-INF/services"));

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.strip().equals(value) && !AdminLog.hasControl(value) && AdminLog.exemptEntry(value, shell);
        }
    }

    /** A collection whose elements are all different. */
    @Documented
    @Constraint(validatedBy = NoDuplicatesValidator.class)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE, ElementType.ANNOTATION_TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface NoDuplicates {
        String message() default "must not hold an element twice";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static final class NoDuplicatesValidator implements ConstraintValidator<NoDuplicates, Collection<?>> {
        @Override
        public boolean isValid(Collection<?> value, ConstraintValidatorContext context) {
            return value == null || new HashSet<>(value).size() == value.size();
        }
    }

    /** A date and time with an offset in ISO-8601 form, which is how the log writes when a change was prepared. */
    @Documented
    @Constraint(validatedBy = OffsetDateTimeTextValidator.class)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE, ElementType.ANNOTATION_TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface OffsetDateTimeText {
        String message() default "must be an ISO-8601 date and time with an offset";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static final class OffsetDateTimeTextValidator implements ConstraintValidator<OffsetDateTimeText, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            try {
                OffsetDateTime.parse(value);
                return true;
            } catch (DateTimeParseException invalid) {
                LOG.debug("The text is not an ISO-8601 date and time with an offset", invalid);
                return false;
            }
        }
    }
}

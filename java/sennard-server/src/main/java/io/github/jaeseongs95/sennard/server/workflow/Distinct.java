// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashSet;
import java.util.List;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/** The items of the list are all different ({@code uniqueItems} in a tool schema); a missing list is left to {@code @NotNull}. */
@Documented
@Constraint(validatedBy = Distinct.Rule.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@interface Distinct {
    String message() default "the items must be distinct";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Checks a list for duplicates. */
    final class Rule implements ConstraintValidator<Distinct, List<?>> {
        @Override
        public boolean isValid(List<?> items, ConstraintValidatorContext context) {
            return items == null || new HashSet<>(items).size() == items.size();
        }
    }
}

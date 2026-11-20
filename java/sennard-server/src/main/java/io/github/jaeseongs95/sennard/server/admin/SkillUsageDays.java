// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/** The number of days of the skill statistics: one of the periods the page offers ({@link AdminPageData#SKILL_USAGE_PERIODS}); null is not this constraint's to refuse. */
@Documented
@Constraint(validatedBy = SkillUsageDays.Validator.class)
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface SkillUsageDays {
    String message() default "must be one of the periods the page offers";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<SkillUsageDays, Integer> {
        @Override
        public boolean isValid(Integer days, ConstraintValidatorContext context) {
            return days == null || AdminPageData.SKILL_USAGE_PERIODS.contains(days);
        }
    }
}

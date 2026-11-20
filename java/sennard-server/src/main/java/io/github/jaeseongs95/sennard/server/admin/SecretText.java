// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * The input rule of a secret that travels in a request as a {@link io.github.jaeseongs95.sennard.domain.secret.SecretValue}: at least one character, at most {@link #max()}
 * characters and no control character (the rule {@code @Size} and {@code @Pattern("^[^\p{Cc}]*$")} state for a text; those constraints do not apply to a secret value, which is not
 * a {@code CharSequence}). A violation names the field, never its value. An absent value is valid, as it is for {@code @Size}.
 */
@Documented
@Constraint(validatedBy = SecretTextValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface SecretText {
    String message() default "The secret is not accepted";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** The most characters the secret has. */
    int max();
}

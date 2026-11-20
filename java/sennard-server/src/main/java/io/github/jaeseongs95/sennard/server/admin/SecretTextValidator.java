// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Checks {@link SecretText}: the characters of the secret are revealed for this check only and cleared at its end. */
public final class SecretTextValidator implements ConstraintValidator<SecretText, SecretValue> {
    private int max;

    @Override
    public void initialize(SecretText constraint) {
        this.max = constraint.max();
    }

    @Override
    public boolean isValid(SecretValue value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        char[] characters = value.reveal();
        try {
            if (characters.length < 1 || characters.length > max) {
                return false;
            }
            for (char character : characters) {
                if (Character.getType(character) == Character.CONTROL) {
                    return false;
                }
            }
            return true;
        } finally {
            SecretValue.clear(characters);
        }
    }
}

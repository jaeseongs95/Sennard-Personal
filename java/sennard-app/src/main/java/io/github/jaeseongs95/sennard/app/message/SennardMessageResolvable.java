// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.message;

import org.springframework.context.MessageSourceResolvable;

import io.github.jaeseongs95.sennard.domain.error.SennardException;

/**
 * An {@link SennardException} as Spring's {@link MessageSourceResolvable} (the domain module has no Spring import, so the adapter is here): the code is the
 * exception's message key and the single argument is its named-argument map, which {@link CatalogMessageSource} formats. Every boundary makes its sentence with
 * {@code messageSource.getMessage(SennardMessageResolvable.of(exception), locale)}.
 */
public record SennardMessageResolvable(SennardException exception) implements MessageSourceResolvable {
    public static SennardMessageResolvable of(SennardException exception) {
        return new SennardMessageResolvable(exception);
    }

    @Override
    public String[] getCodes() {
        return new String[] {exception.messageKey()};
    }

    @Override
    public Object[] getArguments() {
        return new Object[] {exception.arguments()};
    }

    @Override
    public String getDefaultMessage() {
        return exception.code().name();
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one root of what Sennard's own code throws on purpose. Business code states a code and its arguments; it never writes a sentence. The
 * sentence is made at the boundary from the message catalog: {@code SennardMessages} in sennard-app turns this exception into a Spring
 * {@code MessageSourceResolvable} (this module has no Spring import, so the exception exposes what that interface needs: {@link #messageKey()} and
 * {@link #arguments()}). The result is the same in the MCP result and in an HTTP answer, in English or Korean.
 *
 * <p>The seven subclasses are the {@link Category categories}; the hierarchy is sealed so a {@code switch} over an {@code SennardException} with no
 * {@code default} is checked complete by the compiler.
 */
public abstract sealed class SennardException extends RuntimeException
        permits InvalidInputException, NotFoundException, ConflictException, GateRefusedException, BindingException, IntegrityException, UnavailableException {
    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final transient Map<String, Object> arguments;
    private final transient Map<String, Object> details;

    /**
     * @param arguments the values the message sentence uses (named, as the catalog's ICU patterns name them)
     * @param details what the wire's {@code error.details} carries besides {@code reasonCode}; may be empty
     */
    protected SennardException(ErrorCode code, Map<String, Object> arguments, Map<String, Object> details, Throwable cause) {
        super(code.name(), cause); // the message is the code only: values (a body, a path) never reach a log line through the exception text
        this.code = code;
        this.arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details)); // a detail may be null: the wire shows it as null
    }

    public ErrorCode code() {
        return code;
    }

    public Category category() {
        return code.category();
    }

    public String messageKey() {
        return code.messageKey();
    }

    public Map<String, Object> arguments() {
        return arguments;
    }

    public Map<String, Object> details() {
        return details;
    }

    /** A key-value list as a map in the order given. */
    public static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            map.put((String) pairs[index], pairs[index + 1]);
        }
        return map;
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.contract;

import java.util.Locale;
import java.util.function.Consumer;

import com.networknt.schema.ExecutionContext;

/**
 * The language of what a JSON Schema validator says about a value that does not fit. The validator would answer in the default language of the JVM, so one server answered some refusals
 * in English (the tools whose contract is checked at the entrance) and others in the language of the machine (a document checked against a contract inside a use case). These details
 * are read by a model, which gets one language from every tool: the validator's sentences are English, as the contract's own text is. The sentences Sennard writes itself are in the
 * language of the reader ({@code Messages}), and a validator's sentence is only ever the {@code details} beside them.
 */
public final class SchemaLocale {
    /** Passed to {@code Schema.validate(value, ...)}. */
    public static final Consumer<ExecutionContext> IN_ENGLISH = execution -> execution.executionConfig(config -> config.locale(Locale.ENGLISH));

    private SchemaLocale() {
    }
}

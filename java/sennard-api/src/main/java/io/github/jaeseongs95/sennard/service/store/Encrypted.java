// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a component of a row record whose column holds the value sealed by the {@code SecretSealer} (AES-256-GCM, the key kept by the edition). The record carries the plain value; a store that
 * reads or writes rows through the common path of {@code ModuleStore} ({@code list}, {@code single}, {@code upsertRow}) opens the value when it builds the record and seals it when it writes the row, so
 * a service never calls the sealer. The same role as a JPA {@code AttributeConverter}, kept in the store layer.
 *
 * <p>What a sealed value is bound to (the additional data of the sealer, so that the value does not open once it is moved to another row or its binding is changed):
 * <ul>
 * <li>{@link #name()}: the component whose value names the sealed value (the row's identity, for example the row's key). Required, so there is no unbound value.</li>
 * <li>{@link #scope()}: a constant that names what is sealed and separates it from every other use of the sealer. Required; give each sealed column its own.</li>
 * <li>{@link #bind()}: other components of the same record whose values are bound too (for example the fingerprint the value must stay consistent with). They go into the data under their component names.</li>
 * </ul>
 * Changing the value of a bound component means sealing the value again; the stored value is not opened under another binding.
 *
 * <p>What is read once per class: which components are encrypted (cached per class, never per row). A misdeclared record (a type other than {@code String}, a missing component named by
 * {@link #name()} or {@link #bind()}, a bound component that is itself encrypted) is refused when the store starts, not stored in the clear. A {@code null} component stays {@code null} in both directions.
 *
 * <p>Limits. The column holds a random-IV ciphertext, so two equal values differ: an encrypted column cannot be searched by equality, compared, indexed for lookup or ordered by its value in SQL,
 * and a row is found by its other columns. The component is a {@code String}: the plain value lives in the record (and, once read, in an immutable {@code String} that cannot be overwritten), so a
 * record that carries one declares its own {@code toString} that leaves the value out. Only record components take this annotation: a plain field would be skipped by the row mapping, and the
 * compiler refuses the annotation there instead of letting the value be stored in the clear.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Encrypted {
    /** What is sealed, as a constant (the sealer's additional data {@code scope}). */
    String scope();

    /** The component of the same record whose value names the sealed value. */
    String name();

    /** The other components of the same record that the sealed value is bound to. */
    String[] bind() default {};

    /** The column the value is written to; the component's name in snake case when empty. */
    String column() default "";
}

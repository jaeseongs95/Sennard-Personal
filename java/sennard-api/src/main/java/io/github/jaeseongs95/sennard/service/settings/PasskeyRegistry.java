// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings;

import java.util.List;
import java.util.Optional;

/**
 * The passkey public keys of the admin page: registered, looked up and revoked here, never in the business database. Each write ({@link #register}, {@link #revoke}, {@link #clear}) is
 * a use case of the settings database: one service method, one transaction of the settings transaction manager ({@link SettingsTransactions#MANAGER}). The business side calls it
 * from its own transaction without being bound to it (another DataSource), and the writes are idempotent: a revoke that meets another revoke of the same key (the UPDATE waits for the row) and a register that meets a registration that has committed both succeed. A register that overlaps an uncommitted one is a race with another writer, which the database raises as a {@code ConcurrencyFailureException} that the entrances state as the retryable conflict (see {@link #register}).
 */
public interface PasskeyRegistry {
    /**
     * Registers a key as {@link PasskeyState#ACTIVE}. Registering the same key again (the same credential id with the same public key and algorithm, revoked or not) returns the
     * key the registry holds, so a registration that an earlier attempt made, or that another caller made and committed, is the same registration;
     * {@link SettingsCode#PASSKEY_EXISTS} when the credential id is held with another public key or algorithm. A caller that overlaps another one's registration of the same id that is not
     * committed yet meets the database's refusal as it is, a {@code ConcurrencyFailureException}: the database does not wait for an uncommitted unique key (H2 refuses the second INSERT at
     * once), so both succeed when the first has committed before the second writes, and the second sends its request again when it overlapped (the entrances answer it 409 {@code CONFLICT},
     * {@code CONCURRENT_UPDATE}, like every race).
     */
    PasskeyCredential register(PasskeyRegistration registration);

    /** The key of a credential id, whatever its state; empty when it was never registered. */
    Optional<PasskeyCredential> find(String credentialId);

    /** Every key, revoked ones included, in the order they were registered. */
    List<PasskeyCredential> list();

    /** Revokes an active key; a key that is revoked already is returned as it is (the revocation was made, by this caller or another); {@link SettingsCode#PASSKEY_NOT_FOUND} when there is no key of this id. */
    PasskeyCredential revoke(String credentialId);

    /**
     * Deletes every key, revoked ones included: the admin reset, after which the first key registers itself again. The admin reset calls it before it archives the
     * signed log, so a run that stops half way leaves no key to sign with.
     */
    void clear();
}

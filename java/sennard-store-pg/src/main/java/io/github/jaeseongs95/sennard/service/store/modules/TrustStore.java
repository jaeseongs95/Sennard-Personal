// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.modules;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.Predicate;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.service.database.SqlBuilder;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * Source provenance receipts. Only metadata and content digests; never a prompt or message body. The receipt JSON is signed by the service
 * layer and stored byte for byte: this store only keeps the atomicity of "first event wins, a different event with the same key is a
 * conflict". It takes no lock: a message goes to one claimer (the queue's conditional UPDATE), so one claimer records the receipt of a message at a time, and the UNIQUE key
 * of (host, session, event) is what a second receipt of one event would meet. The signing key is made once, at the start of the server, by an INSERT whose primary key refuses
 * a second one (see {@link #insertSigningKey}); the area's module lock is taken by no path of the area's requests.
 */
@Repository
public class TrustStore extends ModuleStore {
    private static final String SIGNING_KEY = "trust-signing-key";

    private final SecureRandom random;
    private final SqlBuilder limit;

    /** @param random the application's one {@link SecureRandom}, which makes the signing key */
    public TrustStore(@Qualifier("storeJdbc") JdbcClient jdbc, SecureRandom random, SqlBuilder limit) {
        super(jdbc);
        this.random = random;
        this.limit = limit;
    }

    /** One receipt's indexed columns plus its stored JSON. */
    public record Receipt(String receiptId, String host, String sessionId, String eventId, String originKind, String authorityEffect,
            String observedAt, String expiresAt, String receiptJson) {
    }

    /** The signing key is a {@link SecretValue}, so the text of the record (a log line, an exception message) never shows it. */
    public record KeyAndReceipt(SecretValue signingKey, String receiptJson) {
    }

    /** The stored text of the key (base64url), null when none: it becomes a {@link SecretValue} at once in the methods that give it out. */
    private String signingKeyText() {
        return firstOrNull(jdbc.sql("SELECT value FROM sennard.trust_metadata WHERE meta_key = ?").params(SIGNING_KEY).query(String.class).list());
    }

    /** Whether the signing key exists (a read). */
    public boolean hasSigningKey() {
        return signingKeyText() != null;
    }

    /**
     * Makes the per-database signing key (32 random bytes, base64url): an INSERT and nothing else. The primary key ({@code meta_key}) refuses a second one with a duplicate-key
     * failure. Its one caller is the start of the server ({@code TrustService#createSigningKeyIfAbsent}, which reads first, in the same transaction). A request path never
     * makes the key, it reads it with {@link #requireSigningKey}.
     */
    public void insertSigningKey(String now) {
        update("INSERT INTO sennard.trust_metadata (meta_key, value, updated_at) VALUES (?, ?, ?)", SIGNING_KEY, StoreValues.randomBase64Url(random, 32), StoreValues.ts(now));
    }

    /** The signing key, read only: a request path never makes it, the start of the server does. A missing key is a failure the caller sees. */
    public SecretValue requireSigningKey() {
        String existing = signingKeyText();
        if (existing == null) {
            throw new StoreException(StoreCode.TRUST_SIGNING_KEY_INVALID, "The trust signing key does not exist: the start of the server makes it.");
        }
        return SecretValue.of(checked(existing));
    }

    private static String checked(String value) {
        if (Base64.getUrlDecoder().decode(value).length != 32) {
            throw new StoreException(StoreCode.TRUST_SIGNING_KEY_INVALID, "Stored trust signing key is invalid.");
        }
        return value;
    }

    private String receiptBy(String condition, Object... params) {
        return firstOrNull(jdbc.sql("SELECT receipt_json FROM sennard.input_source_receipts WHERE " + condition).params(params).query(String.class).list());
    }

    /**
     * Stores the receipt, or returns the receipt already stored for the same (host, session, event) when {@code sameSecurityMetadata}
     * accepts it. A different stored receipt is REQUEST_CONFLICT.
     */
    public String recordInputSource(Receipt receipt, Predicate<String> sameSecurityMetadata) {
        String prior = receiptBy("host = ? AND session_id = ? AND event_id = ?", receipt.host(), receipt.sessionId(), receipt.eventId());
        if (prior != null) {
            if (sameSecurityMetadata.test(prior)) {
                return prior;
            }
            throw new StoreException(StoreCode.REQUEST_CONFLICT, "TRUST_SOURCE_EVENT_CONFLICT: this input event already has a receipt with different security metadata.");
        }
        update("INSERT INTO sennard.input_source_receipts (receipt_id, host, session_id, event_id, origin_kind, authority_effect, observed_at, expires_at, receipt_json) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", receipt.receiptId(), receipt.host(), receipt.sessionId(), receipt.eventId(), receipt.originKind(),
                receipt.authorityEffect(), StoreValues.ts(receipt.observedAt()), StoreValues.ts(receipt.expiresAt()), receipt.receiptJson());
        return receipt.receiptJson();
    }

    /** The newest receipt of a session, or null. */
    public String latestInputSource(String host, String sessionId) {
        return receiptBy(limit.applyLimit("host = ? AND session_id = ? ORDER BY observed_at DESC, receipt_id DESC", "?"), host, sessionId, 1);
    }

    public String getInputSource(String receiptId) {
        return receiptBy("receipt_id = ?", receiptId);
    }

    /** The key and the receipt for an out-of-band verification; both null when absent. Reads in one snapshot, takes no lock, creates no key. */
    public KeyAndReceipt readForVerification(String receiptId) {
        String key = signingKeyText();
        return new KeyAndReceipt(key == null ? null : SecretValue.of(key), getInputSource(receiptId));
    }
}

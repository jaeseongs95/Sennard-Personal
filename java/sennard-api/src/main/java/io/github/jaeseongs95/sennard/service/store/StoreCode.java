// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store;

/**
 * The reason of a {@link StoreException}: a constant of this enum, not a text, so a caller's {@code switch} or comparison names a constant the compiler checks. The
 * constant's name is the code a failure carries in a log, a hook rejection or a tool answer.
 */
public enum StoreCode {
    // what the database's answer says (the store's exception translator)
    STORE_BUSY, STORE_CONFLICT, STORE_UNIQUE_VIOLATION, STORE_TIMEOUT, STORE_RESOURCE_EXHAUSTED, INVALID_INPUT, STORE_RESULT_UNKNOWN, STORE_UNREACHABLE, STORE_FAILURE,
    STORE_POOL_EXHAUSTED, STORE_PREPARING,
    // what a store method refuses
    INTEGRITY_FAILED, STALE_REVISION, LEASE_CONFLICT, ROOT_CONFLICT, REQUEST_CONFLICT, USER_APPROVAL_SOURCE_CONFLICT, CONTINUITY_CLEANUP_STALE, CONTINUITY_SECRET_MISSING,
    TRUST_SIGNING_KEY_INVALID, MCP_UNAVAILABLE,
    // the database's configuration, credentials and encoding
    INVALID_DATABASE_CONFIG, BUSINESS_DATABASE_NOT_H2, DB_DRIVER_NOT_FOUND, DB_DRIVER_REJECTS_URL, UNSUPPORTED_DATABASE_PRODUCT, DATABASE_PRODUCT_MISMATCH, REMOTE_DB_TLS_NOT_VERIFIED, DB_CREDENTIAL_UNREADABLE, DB_ENCODING_MISMATCH, SESSION_PEPPER_UNAVAILABLE,
    // the migrations
    MIGRATION_FAILED, MIGRATION_CHANGED, MIGRATION_MISSING, MIGRATION_OUT_OF_ORDER, MIGRATION_GAP
    // (a folder that is not what the storage of secrets needs is a PRIVATE_STORAGE_* reason of UnavailableCode, from the port PrivateStorage)
}

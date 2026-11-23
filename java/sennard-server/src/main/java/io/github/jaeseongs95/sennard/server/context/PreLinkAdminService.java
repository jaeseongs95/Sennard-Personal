// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.GateCode;
import io.github.jaeseongs95.sennard.domain.error.GateRefusedException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.NotFoundCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkRequest;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkSettings;
import io.github.jaeseongs95.sennard.service.settings.DatabaseTls;
import io.github.jaeseongs95.sennard.service.settings.PasskeyCredential;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistration;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistry;
import io.github.jaeseongs95.sennard.service.settings.PasskeyState;
import io.github.jaeseongs95.sennard.service.settings.SettingsException;
import io.github.jaeseongs95.sennard.service.settings.SettingsTransactions;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore.LogRecord;
import io.github.jaeseongs95.sennard.service.store.modules.StoreValues;
import io.github.jaeseongs95.sennard.service.store.pg.SennardDatabaseConfig;
import io.github.jaeseongs95.sennard.service.store.pg.SennardDatabaseConfig.TlsPolicy;
import tools.jackson.databind.JsonNode;

/**
 * The admin page before the business database is ready: the passkeys and the link of the business database, signed with a passkey like every other change of the admin log, in
 * the settings database. The business database is not there, so the signed records are appended to the section of the log before the link ({@code PreLinkAdminRecords}) in
 * the settings database, with the same record form, the same chain rules and the same checks as the business log ({@link ContextLayerService}); only the changes the
 * settings database can hold are taken here (a passkey's registration and revocation, and {@link AdminChange.DatabaseLink}), and any other waits for the business database.
 *
 * <p>Each use case is one method and one transaction of the settings database: the record, the registry's key and the link settings are one write. The section is open until
 * the business database is ready for the first time; the page is routed here only while it is open ({@code AdminController}).
 */
@Service
public class PreLinkAdminService {
    private final AdminLogSource logs;
    private final AdminLogVerifier verifier;
    private final AdminCommitChecks commitChecks;
    private final AdminIssuedIntents issuedIntents;
    private final PasskeyRegistry passkeys;
    private final DatabaseLinkSettings links;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PreLinkAdminService(AdminLogSource logs, AdminLogVerifier verifier, AdminCommitChecks commitChecks, AdminIssuedIntents issuedIntents, PasskeyRegistry passkeys,
            DatabaseLinkSettings links, ApplicationEventPublisher events, Clock clock) {
        this.logs = logs;
        this.verifier = verifier;
        this.commitChecks = commitChecks;
        this.issuedIntents = issuedIntents;
        this.passkeys = passkeys;
        this.links = links;
        this.events = events;
        this.clock = clock;
    }

    /** How a link's protection is spelled by the store that connects with it. */
    public static TlsPolicy tlsPolicy(DatabaseTls tls) {
        return switch (tls) {
            case LOOPBACK_PLAINTEXT -> TlsPolicy.LOOPBACK_PLAINTEXT;
            case VERIFY_FULL -> TlsPolicy.VERIFY_FULL;
        };
    }

    private static InvalidInputException changeInvalid(Map<String, Object> details) {
        return new InvalidInputException(InputCode.ADMIN_CHANGE_INVALID, Map.of(), details);
    }

    /**
     * What the page shows before the business database is ready: the keys and the failed records of the log before the link. Everything else is the business database's and is empty.
     * A read: the keys the log revoked are handed on ({@code revokedKeys}) and the entrance brings the registry in step with them (see {@link ContextLayerService#adminState}).
     */
    @Transactional(transactionManager = SettingsTransactions.MANAGER, readOnly = true)
    public ContextLayerService.AdminPageState adminState() {
        AdminLog.State state = verifier.fold(logs.preLinkRecords());
        List<ContextLayerService.FailureView> failures = state.failures.stream().map(failure -> new ContextLayerService.FailureView(failure.target(), failure.seq(), failure.code(),
                failure.change(), ContextLayerService.failureLayer(failure.target()), ContextLayerService.headSeq(state, failure.target()))).toList();
        return new ContextLayerService.AdminPageState(ContextLayerService.keyViews(state), state.bootstrap, List.of(), null, List.of(), List.of(), List.of(), List.of(), failures,
                new ContextLayerService.SignedSettings(null, List.of(), null, null, null, null, null, null, null), state.lastSeq, List.copyOf(state.revoked));
    }

    @Transactional(transactionManager = SettingsTransactions.MANAGER, readOnly = true)
    public Optional<LogRecord> recordByRequest(String requestId) {
        return logs.preLinkByRequest(requestId);
    }

    /** Only the changes the settings database can hold: the others need the business database. */
    private static void checkTaken(AdminChange change) {
        switch (change) {
            case AdminChange.KeyAdd _, AdminChange.KeyRevoke _, AdminChange.DatabaseLink _ -> {
                // Taken here.
            }
            case AdminChange.GlobalSet _, AdminChange.RoleAdd _, AdminChange.RoleRename _, AdminChange.RoleDelete _, AdminChange.RoleBodySet _, AdminChange.BindingSet _,
                    AdminChange.Ack _, AdminChange.JevSet _, AdminChange.JevKeySet _, AdminChange.JevKeyDelete _, AdminChange.EditExemptSet _, AdminChange.JvmPresetSet _,
                    AdminChange.ConvergenceLimitsSet _, AdminChange.ResourceLimitsSet _, AdminChange.AttachmentStorageSet _, AdminChange.RootApprove _, AdminChange.RootRelease _, AdminChange.WorkflowPlanUnblock _ ->
                throw changeInvalid(Map.of("reason", "BUSINESS_DATABASE_NOT_READY"));
        }
    }

    /**
     * The link as the settings database will keep it, checked the way the store checks it when it connects (the product, the TLS rule, the account, the files' form): a link that
     * could never connect is refused before the passkey is asked. The one check of a link, for the page before the business database is ready (this service) and after it
     * ({@link ContextLayerService}).
     */
    static DatabaseLinkRequest checkedLink(AdminChange.DatabaseLink link) {
        DatabaseLinkRequest request;
        try {
            request = new DatabaseLinkRequest(Path.of(link.driverPath()), link.jdbcUrl(), link.accountName(), Path.of(link.passwordRef()), link.tls());
        } catch (SettingsException | InvalidPathException invalid) {
            throw new InvalidInputException(InputCode.ADMIN_CHANGE_INVALID, Map.of(), Map.of("reason", "DATABASE_LINK_INVALID"), invalid);
        }
        // The store's own rules (H2 is no business database, the product must be one Sennard has SQL for, the TLS rule): StoreException, which the application's error translation states.
        new SennardDatabaseConfig(request.jdbcUrl(), request.accountName(), request.passwordRef(), request.driverPath(), tlsPolicy(request.tls()), 1);
        return request;
    }

    /** The intent the passkey signs, checked first (see {@link ContextLayerService#prepareIntent}); it writes no row. */
    @Transactional(transactionManager = SettingsTransactions.MANAGER, readOnly = true)
    public ContextLayerService.PreparedIntent prepareIntent(JsonNode requested, boolean bootstrap, boolean baseHashGiven, String baseHash) {
        AdminChange change = verifier.bindChange(requested);
        checkTaken(change);
        AdminLog.State state = verifier.fold(logs.preLinkRecords());
        AdminLog.requireIntact(state);
        if (bootstrap && !state.keys.isEmpty()) {
            Map<String, Object> details = new HashMap<>();
            details.put("bootstrap", state.bootstrap);
            throw new ConflictException(ConflictCode.ADMIN_ALREADY_BOOTSTRAPPED, Map.of(), details);
        }
        if (!bootstrap && state.keys.isEmpty()) {
            throw new GateRefusedException(GateCode.ADMIN_KEY_NOT_REGISTERED);
        }
        AdminLog.requireSinglePasskey(state, change);
        if (change instanceof AdminChange.DatabaseLink link) {
            checkedLink(link);
        }
        AdminLogVerifier.KeyBook book = verifier.keys();
        List<String> usable = bootstrap ? List.of()
                : state.keys.keySet().stream().filter(id -> book.find(id).map(key -> key.state() == PasskeyState.ACTIVE).orElse(false)).toList();
        if (!bootstrap && usable.isEmpty()) {
            throw new GateRefusedException(GateCode.ADMIN_KEY_UNKNOWN);
        }
        String signer = !bootstrap ? usable.getFirst() : change instanceof AdminChange.KeyAdd add ? add.credentialId() : null;
        AdminLog.checkChange(state, change, signer);
        AdminLog.Head current = state.heads.get(AdminLog.changeTarget(change));
        String head = current == null ? null : current.recordHash();
        if (baseHashGiven && !Objects.equals(baseHash, head)) {
            Map<String, Object> details = new HashMap<>();
            details.put("actualStateToken", head);
            throw new ConflictException(ConflictCode.ADMIN_TARGET_STALE, Map.of(), details);
        }
        String signed = verifier.buildIntent(change, head, StoreValues.iso(clock.millis()));
        issuedIntents.issue(signed);
        return new ContextLayerService.PreparedIntent(signed, AdminWebAuthn.intentChallenge(signed), bootstrap ? List.of(signer) : new ArrayList<>(usable));
    }

    /**
     * Appends one signed record to the section of the log before the link, with its key and, for a link, the link settings, in one transaction of the settings database (see
     * {@link ContextLayerService#commit}: the same checks, in the same order). A repeated request id returns the record already stored.
     */
    @Transactional(transactionManager = SettingsTransactions.MANAGER)
    public ContextLayerService.CommitResult commit(ContextLayerService.CommitInput input) {
        long now = clock.millis();
        AdminLog.Intent intent = verifier.parseIntent(input.intent());
        AdminChange change = intent.change();
        checkTaken(change);
        if (input.secret() != null) {
            throw changeInvalid(Map.of("reason", "SECRET_UNEXPECTED"));
        }
        if (input.proposalId() != null) {
            throw new NotFoundException(NotFoundCode.ADMIN_PROPOSAL_NOT_FOUND);
        }
        PasskeyRegistration passkey = ContextLayerService.passkeyOf(change, input.publicKey());
        Optional<LogRecord> existing = logs.preLinkByRequest(input.requestId());
        if (existing.isPresent()) {
            return new ContextLayerService.CommitResult(existing.get(), true);
        }
        commitChecks.checkFresh(input.intent(), intent, input.assertion(), input.origin(), now);
        AdminLog.State state = verifier.fold(logs.preLinkRecords());
        AdminLog.requireIntact(state);
        if (change instanceof AdminChange.KeyAdd && intent.prevTargetHash() == null && !state.keys.isEmpty()) {
            Map<String, Object> details = new HashMap<>();
            details.put("bootstrap", state.bootstrap);
            throw new ConflictException(ConflictCode.ADMIN_ALREADY_BOOTSTRAPPED, Map.of(), details);
        }
        AdminLog.requireSinglePasskey(state, change);
        DatabaseLinkRequest request = change instanceof AdminChange.DatabaseLink link ? checkedLink(link) : null;
        AdminWebAuthn.Assertion assertion = input.assertion();
        String hash = verifier.recordHash(input.intent(), assertion.credentialId(), assertion.authenticatorData(), assertion.clientDataJson(), assertion.signature());
        LogRecord record = new LogRecord(state.lastSeq + 1, intent.target(), input.intent(), assertion.credentialId(), assertion.authenticatorData(), assertion.clientDataJson(),
                assertion.signature(), hash, StoreValues.iso(now));
        AdminLogVerifier.KeyBook keys = verifier.keys();
        if (passkey != null) {
            keys = keys.with(new PasskeyCredential(passkey, PasskeyState.ACTIVE, clock.instant(), null));
        }
        verifier.verifyIntent(state, record, intent, keys);
        verifier.requireActive(keys, assertion.credentialId());
        // The key, the record and the link settings are one write of the settings database: this transaction (the registry's own write joins it). A key the registry holds already with the
        // same bytes (an earlier attempt) is taken again.
        if (passkey != null) {
            passkeys.register(passkey);
        }
        LogRecord appended = logs.appendPreLink(record, input.requestId());
        switch (change) {
            case AdminChange.DatabaseLink _ -> {
                links.save(request);
                events.publishEvent(new DatabaseLinkSaved());
            }
            case AdminChange.KeyRevoke revoke -> passkeys.revoke(revoke.credentialId());
            case AdminChange.KeyAdd _ -> {
                // The key was registered above; the record names it.
            }
            case AdminChange.GlobalSet _, AdminChange.RoleAdd _, AdminChange.RoleRename _, AdminChange.RoleDelete _, AdminChange.RoleBodySet _, AdminChange.BindingSet _,
                    AdminChange.Ack _, AdminChange.JevSet _, AdminChange.JevKeySet _, AdminChange.JevKeyDelete _, AdminChange.EditExemptSet _, AdminChange.JvmPresetSet _,
                    AdminChange.ConvergenceLimitsSet _, AdminChange.ResourceLimitsSet _, AdminChange.AttachmentStorageSet _, AdminChange.RootApprove _, AdminChange.RootRelease _, AdminChange.WorkflowPlanUnblock _ -> throw new IllegalStateException("Refused before this point (checkTaken).");
        }
        events.publishEvent(new AdminIntentSpent(input.intent()));
        return new ContextLayerService.CommitResult(appended, false);
    }
}

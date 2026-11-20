// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.GateCode;
import io.github.jaeseongs95.sennard.domain.error.GateRefusedException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.service.settings.PasskeyCredential;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistration;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistry;
import io.github.jaeseongs95.sennard.service.settings.PasskeyState;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore.LogRecord;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.exc.InvalidTypeIdException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * Reads, builds and verifies the signed intents of the admin log. An intent is read once from its canonical text ({@code readTree} to compare the
 * text with its canonical form, then one binding of that tree to {@link AdminLog.Intent}), and the shape of every kind of change is the record and
 * the constraints of its {@link AdminChange} variant, checked by the validator. The reading mapper is the server's with strict binding: an unknown
 * key, a missing key, a null for a primitive and a scalar of another JSON type (a number for text, text for a number) are refused, because what was
 * signed is the text, and a record that read something else from it would be a different change.
 */
@Component
public class AdminLogVerifier {
    private static final Logger LOG = LoggerFactory.getLogger(AdminLogVerifier.class);

    /** The five parts of a record that its hash covers (the same keys the page sends the assertion with). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SignedParts(String intent, String credentialId, String authenticatorData, @JsonProperty("clientDataJSON") String clientDataJson, String signature) {
    }

    /**
     * How many results are kept of each kind. A log is a handful of records (a reset archives it), so this is a bound for a log that was never reset, not a size a
     * normal one reaches; when it is reached the memory is dropped and refills from the next fold.
     */
    private static final int REMEMBERED_LIMIT = 4096;

    private final JsonMapper mapper;
    private final Validator validator;
    private final AdminWebAuthn webauthn;
    private final PasskeyRegistry passkeys;
    /**
     * The intent a record's text was read to, by the record's hash, which covers that text: the same text is the same intent. A record is folded by every hook that
     * reads the shared context, so reading and validating its intent each time is work done again for a result that cannot differ.
     */
    private final Map<String, AdminLog.Intent> readIntents = new ConcurrentHashMap<>();
    /**
     * The signatures that verified: a digest of the record's signed parts with the public key and algorithm they were checked against. Only a success is remembered (a
     * failure is checked again), and a signature checked against another key is another entry, so a record whose key moved is not taken as verified.
     */
    private final Set<String> verifiedSignatures = ConcurrentHashMap.newKeySet();
    private final AtomicLong signatureChecks = new AtomicLong();

    public AdminLogVerifier(JsonMapper mapper, Validator validator, AdminWebAuthn webauthn, PasskeyRegistry passkeys) {
        this.passkeys = passkeys;
        this.mapper = mapper.rebuild()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .withCoercionConfig(LogicalType.Textual, text -> text.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail).setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Enum, name -> name.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail))
                .build();
        this.validator = validator;
        this.webauthn = webauthn;
    }

    private static InvalidInputException noncanonical() {
        return new InvalidInputException(InputCode.ADMIN_INTENT_NONCANONICAL);
    }

    /** The same refusal for a text that failed to read: the failure stays as its cause. */
    private static InvalidInputException noncanonical(Throwable cause) {
        return new InvalidInputException(InputCode.ADMIN_INTENT_NONCANONICAL, Map.of(), Map.of(), cause);
    }

    private static String shortened(String text, int max) {
        return text.substring(0, Math.min(text.length(), max));
    }

    /** A change that is not one of the kinds or does not have the shape of its kind; {@code op} is the kind as far as it is known. */
    private static InvalidInputException changeInvalid(String op) {
        return new InvalidInputException(InputCode.ADMIN_CHANGE_INVALID, Map.of(), opDetails(op));
    }

    /** The same refusal for a change that failed to bind: the failure stays as its cause. */
    private static InvalidInputException changeInvalid(String op, Throwable cause) {
        return new InvalidInputException(InputCode.ADMIN_CHANGE_INVALID, Map.of(), opDetails(op), cause);
    }

    private static Map<String, Object> opDetails(String op) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("op", op == null || op.isEmpty() ? null : shortened(op, 40));
        return details;
    }

    private static String kindOf(AdminChange change) {
        return change == null ? null : AdminOp.of(change).name();
    }

    /** The type id the reader found in a change that names no kind, null for any other failure. */
    private static String unknownKind(JacksonException failure) {
        return failure instanceof InvalidTypeIdException unknown ? unknown.getTypeId() : null;
    }

    /** Whether a failure is in the change (the key {@code change} of the intent) and not in the intent around it. */
    private static boolean inChange(JacksonException failure) {
        List<JacksonException.Reference> path = failure.getPath();
        // The key is the intent's component of the change's type (never a text written here): a rename of the component keeps the check.
        return !path.isEmpty() && Arrays.stream(AdminLog.Intent.class.getRecordComponents())
                .anyMatch(component -> component.getType() == AdminChange.class && component.getName().equals(path.getFirst().getPropertyName()));
    }

    /** Whether a violation is of the intent's own values (version, domain, relying party, target, hash, time) and not of the change or something inside it. */
    private static boolean inIntent(ConstraintViolation<?> violation) {
        return violation.getLeafBean() instanceof AdminLog.Intent;
    }

    /** Binds the change a page asked for (a JSON object), refusing what is not one of the kinds or does not have the shape of its kind. */
    public AdminChange bindChange(JsonNode node) {
        if (node == null) {
            throw changeInvalid(null);
        }
        AdminChange change;
        try {
            change = mapper.treeToValue(node, AdminChange.class);
        } catch (JacksonException unreadable) {
            throw changeInvalid(unknownKind(unreadable), unreadable);
        }
        if (change == null) {
            // The JSON text null binds to nothing.
            throw changeInvalid(null);
        }
        if (!validator.validate(change).isEmpty()) {
            throw changeInvalid(kindOf(change));
        }
        return change;
    }

    /** The intent a passkey is asked to sign: this change on top of the chain head it was made for. */
    public AdminLog.Intent intent(AdminChange change, String prevTargetHash, String issuedAt) {
        return new AdminLog.Intent(AdminLog.INTENT_VERSION, AdminLog.DOMAIN, AdminWebAuthn.RP_ID, AdminLog.changeTarget(change), prevTargetHash, change, issuedAt);
    }

    /** The canonical text of an intent, the exact bytes the signature covers. */
    public String buildIntent(AdminChange change, String prevTargetHash, String issuedAt) {
        JsonNode tree = mapper.valueToTree(intent(change, prevTargetHash, issuedAt));
        return CanonicalJson.of(tree);
    }

    /** The hash a record is stored under; any change to its signed parts changes it. */
    public String recordHash(String intent, String credentialId, String authenticatorData, String clientDataJson, String signature) {
        JsonNode tree = mapper.valueToTree(new SignedParts(intent, credentialId, authenticatorData, clientDataJson, signature));
        return CanonicalJson.digest(tree);
    }

    /** The tree of a text that is the exact canonical form of its JSON; refused as non-canonical otherwise (or when it is no JSON). */
    private JsonNode canonicalTree(String text) {
        try {
            JsonNode tree = mapper.readTree(text);
            if (CanonicalJson.of(tree).equals(text)) {
                return tree;
            }
        } catch (JacksonException | IllegalArgumentException malformed) {
            throw noncanonical(malformed);
        }
        throw noncanonical();
    }

    /** Parses a stored or submitted intent; only the exact canonical form of a well-formed intent is accepted. */
    public AdminLog.Intent parseIntent(String text) {
        JsonNode tree = canonicalTree(text);
        AdminLog.Intent intent;
        try {
            intent = mapper.treeToValue(tree, AdminLog.Intent.class);
        } catch (JacksonException unreadable) {
            throw inChange(unreadable) ? changeInvalid(unknownKind(unreadable), unreadable) : noncanonical(unreadable);
        }
        if (intent == null) {
            throw noncanonical();
        }
        Set<ConstraintViolation<AdminLog.Intent>> violations = validator.validate(intent);
        if (!violations.isEmpty()) {
            // A fault of the intent itself is reported before a fault of the change it carries.
            throw violations.stream().anyMatch(AdminLogVerifier::inIntent) ? noncanonical() : changeInvalid(kindOf(intent.change()));
        }
        String chain = AdminLog.changeTarget(intent.change());
        if (!chain.equals(intent.target())) {
            throw new InvalidInputException(InputCode.ADMIN_CHANGE_INVALID, Map.of(), Map.of("target", shortened(intent.target(), 100)));
        }
        return intent;
    }

    /** Checks the hash of a record: the one it is stored under is the hash of its signed parts. */
    private void checkHash(LogRecord record) {
        if (!recordHash(record.intent(), record.credentialId(), record.authenticatorData(), record.clientDataJson(), record.signature()).equals(record.recordHash())) {
            throw new InvalidInputException(InputCode.ADMIN_RECORD_ALTERED);
        }
    }

    /** The intent of a record whose hash was checked: read once per hash, then remembered. */
    private AdminLog.Intent intentOf(LogRecord record) {
        AdminLog.Intent known = readIntents.get(record.recordHash());
        if (known != null) {
            return known;
        }
        AdminLog.Intent read = parseIntent(record.intent());
        if (readIntents.size() >= REMEMBERED_LIMIT) {
            readIntents.clear();
        }
        readIntents.put(record.recordHash(), read);
        return read;
    }

    /** How many signatures were really verified, not taken from what an earlier fold verified: a hook that folds an unchanged log adds none. */
    public long signatureChecks() {
        return signatureChecks.get();
    }

    /** Checks the signature of a record against its key, unless the same parts verified against the same key before. */
    private void checkSignature(LogRecord record, String publicKey, int alg) {
        String remembered = recordHash(record.intent(), record.credentialId(), record.authenticatorData(), record.clientDataJson(), record.signature()) + ":" + alg + ":" + publicKey;
        if (verifiedSignatures.contains(remembered)) {
            return;
        }
        signatureChecks.incrementAndGet();
        webauthn.verify(record.intent(), new AdminWebAuthn.Assertion(record.credentialId(), record.authenticatorData(), record.clientDataJson(), record.signature()), publicKey, alg);
        if (verifiedSignatures.size() >= REMEMBERED_LIMIT) {
            verifiedSignatures.clear();
        }
        verifiedSignatures.add(remembered);
    }

    /**
     * The keys of the passkey registry at one moment: what a fold or a commit checks signatures with. A key is looked up whatever its state is, because a record signed
     * before its key was revoked is checked with that key's bytes again at every fold; whether a key may sign <em>now</em> is {@link #requireActive}'s.
     */
    public record KeyBook(Map<String, PasskeyCredential> byId) {
        public KeyBook {
            byId = Map.copyOf(byId);
        }

        public static KeyBook of(List<PasskeyCredential> credentials) {
            Map<String, PasskeyCredential> byId = new LinkedHashMap<>();
            credentials.forEach(credential -> byId.put(credential.registration().credentialId(), credential));
            return new KeyBook(byId);
        }

        /** This book and a key that is about to be registered; a key the registry already holds under that id stays as the registry has it. */
        public KeyBook with(PasskeyCredential candidate) {
            Map<String, PasskeyCredential> all = new LinkedHashMap<>(byId);
            all.putIfAbsent(candidate.registration().credentialId(), candidate);
            return new KeyBook(all);
        }

        Optional<PasskeyCredential> find(String credentialId) {
            return Optional.ofNullable(byId.get(credentialId));
        }
    }

    /** The registry's keys now, for a fold or a commit. */
    public KeyBook keys() {
        return KeyBook.of(passkeys.list());
    }

    /** A key that signs a new record must be active in the registry too (the log decides that it is registered; the registry, that it is not revoked there). */
    public void requireActive(KeyBook keys, String credentialId) {
        PasskeyCredential credential = keys.find(credentialId).orElseThrow(() -> new GateRefusedException(GateCode.ADMIN_KEY_UNKNOWN));
        if (credential.state() != PasskeyState.ACTIVE) {
            throw new GateRefusedException(GateCode.ADMIN_KEY_REVOKED);
        }
    }

    /** Verifies one record against the state before it; throws the reason it cannot be applied. */
    public AdminLog.Intent verifyRecord(AdminLog.State state, LogRecord record, KeyBook keys) {
        checkHash(record);
        AdminLog.Intent intent = intentOf(record);
        verifyIntent(state, record, intent, keys);
        return intent;
    }

    /**
     * What is left of {@link #verifyRecord} for a record whose hash was just made and whose intent was just read: target, chain, change and signature. The signature is checked
     * with the key the registry holds for the record's credential, which must be the key the log names by its fingerprint: a key the registry lacks is an unknown key, and a key
     * the registry holds in another form than the log's fingerprint is a failed record (the settings database was changed, or the log was).
     */
    public void verifyIntent(AdminLog.State state, LogRecord record, AdminLog.Intent intent, KeyBook keys) {
        if (!intent.target().equals(record.target())) {
            throw new InvalidInputException(InputCode.ADMIN_CHANGE_INVALID, Map.of(), Map.of("reason", "TARGET_MISMATCH"));
        }
        AdminLog.Head head = state.heads.get(intent.target());
        String expected = head == null ? null : head.recordHash();
        if (expected == null ? intent.prevTargetHash() != null : !expected.equals(intent.prevTargetHash())) {
            throw new ConflictException(ConflictCode.ADMIN_TARGET_STALE);
        }
        AdminChange change = intent.change();
        AdminLog.checkChange(state, change, record.credentialId());
        AdminLog.KeyState known = state.keys.get(record.credentialId());
        String signedFingerprint;
        int alg;
        if (known != null) {
            signedFingerprint = known.fingerprint();
            alg = known.alg();
        } else if (change instanceof AdminChange.KeyAdd add) {
            signedFingerprint = add.fingerprint();
            alg = add.alg();
        } else {
            throw new GateRefusedException(GateCode.ADMIN_KEY_UNKNOWN);
        }
        PasskeyRegistration registered = registeredAs(keys, record.credentialId(), signedFingerprint, alg);
        if (change instanceof AdminChange.KeyAdd add) {
            // The key a record registers is checked in the registry as the signer is, so a key that never signs (a second key) is held to the log as well.
            registeredAs(keys, add.credentialId(), add.fingerprint(), add.alg());
        }
        checkSignature(record, registered.publicKey(), alg);
    }

    /**
     * The registry's key of a credential id, which must be the key the log names by its fingerprint and algorithm. A key the registry does not hold, or holds as another key, is an
     * integrity failure ({@code ADMIN_RECORD_ALTERED}), not an unknown key: the log says the key was registered, so the registry was lost or changed, and the log then must not fold to "no
     * key", which is the state in which the first key registers itself.
     */
    private static PasskeyRegistration registeredAs(KeyBook keys, String credentialId, String fingerprint, int alg) {
        PasskeyRegistration registered = keys.find(credentialId)
                .orElseThrow(() -> new InvalidInputException(InputCode.ADMIN_RECORD_ALTERED, Map.of(), Map.of("reason", "KEY_NOT_IN_REGISTRY"))).registration();
        if (!AdminWebAuthn.fingerprint(registered.publicKey()).equals(fingerprint) || registered.algorithm().cose() != alg) {
            throw new InvalidInputException(InputCode.ADMIN_RECORD_ALTERED, Map.of(), Map.of("reason", "KEY_FINGERPRINT_MISMATCH"));
        }
        return registered;
    }

    /** The change of a record whose intent can be read, null otherwise. */
    private AdminChange changeOrNull(String intentText) {
        try {
            return parseIntent(intentText).change();
        } catch (SennardException unreadable) {
            LOG.debug("The intent of a failed record cannot be read either", unreadable);
            return null;
        }
    }

    /** A tampered record also invalidates the records chained after it: they fail on the chain head. */
    public AdminLog.State fold(List<LogRecord> records) {
        AdminLog.State state = new AdminLog.State();
        List<AdminLog.Failure> failed = new ArrayList<>();
        // The registry is read once for the whole fold: a hook folds the log at every call, and a statement per record would be work done again for the same keys.
        KeyBook keys = records.isEmpty() ? new KeyBook(Map.of()) : keys();
        for (LogRecord record : records) {
            state.lastSeq = Math.max(state.lastSeq, record.seq());
            AdminLog.Intent intent = null;
            try {
                checkHash(record);
                intent = intentOf(record);
                verifyIntent(state, record, intent, keys);
                AdminLog.apply(state, record, intent);
                state.heads.put(intent.target(), new AdminLog.Head(record.recordHash(), record.seq()));
                state.keyLastUsed.put(record.credentialId(), record.createdAt());
            } catch (SennardException error) {
                LOG.debug("A record of the admin log is refused: {}", error.code(), error);
                // The failure shows the change when the intent can be read: the one read above, or (for a record altered before it was read) one read now.
                failed.add(new AdminLog.Failure(record.target(), record.seq(), error.code().name(), intent != null ? intent.change() : changeOrNull(record.intent())));
            }
        }
        for (AdminLog.Failure failure : failed) {
            AdminLog.Head head = state.heads.get(failure.target());
            if (failure.seq() > (head == null ? 0 : head.seq())) {
                state.failures.add(failure);
            }
        }
        return state;
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import io.github.jaeseongs95.sennard.domain.error.GateCode;
import io.github.jaeseongs95.sennard.domain.error.GateRefusedException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.server.web.AdminPageAddress;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * WebAuthn assertion checks for the admin page, with the JDK's own signature providers. The relying party is always {@code localhost}
 * (WebAuthn does not accept an IP address); the page sends the SPKI key the browser exposes at registration, so no CBOR parsing is needed.
 */
@Component
public final class AdminWebAuthn {
    public static final String RP_ID = AdminPageAddress.HOST;
    private static final Logger LOG = LoggerFactory.getLogger(AdminWebAuthn.class);
    private static final List<String> KEY_TYPES = List.of("EC", "RSA", "Ed25519");
    /** The authenticator data of WebAuthn: the SHA-256 of the RP ID (32 bytes), the flags (1 byte) and the signature counter (4 bytes). */
    private static final int RP_ID_HASH_LENGTH = 32;
    private static final int AUTHENTICATOR_DATA_MIN_LENGTH = RP_ID_HASH_LENGTH + 1 + 4;
    private static final int FLAG_USER_PRESENT = 0x01;
    private static final int FLAG_USER_VERIFIED = 0x04;

    public record Assertion(String credentialId, String authenticatorData, String clientDataJson, String signature) {
    }

    /** What the browser puts in {@code clientDataJSON} that this check reads; the rest (cross-origin flags and the like) is the browser's own. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClientData(ClientDataType type, String challenge, String origin) {
    }

    /** The two types of client data the browser writes; only an assertion ({@code webauthn.get}) is accepted here. */
    public enum ClientDataType {
        GET("webauthn.get"),
        CREATE("webauthn.create");

        private final String wire;

        ClientDataType(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    private record Algorithm(String keyType, String signature) {
    }

    private static final Map<Integer, Algorithm> ALGORITHMS = Map.of(-7, new Algorithm("EC", "SHA256withECDSA"), -257, new Algorithm("RSA", "SHA256withRSA"),
            -8, new Algorithm("Ed25519", "Ed25519"));

    private final JsonMapper mapper;

    public AdminWebAuthn(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** The bytes a base64url value (as the browser writes it) stands for; refused as an invalid signature when it is not base64url. */
    static byte[] bytes(String value, String field) {
        if (value == null) {
            throw signatureInvalid(field);
        }
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException invalid) {
            throw signatureInvalid(field, invalid);
        }
    }

    /** The assertion's {@code field} is missing, not what it must be, or does not decode: refused as an invalid signature. */
    private static InvalidInputException signatureInvalid(String field) {
        return new InvalidInputException(InputCode.ADMIN_SIGNATURE_INVALID, Map.of(), Map.of("field", field));
    }

    /** The same refusal for a field that failed to decode: the failure stays as its cause. */
    private static InvalidInputException signatureInvalid(String field, Throwable cause) {
        return new InvalidInputException(InputCode.ADMIN_SIGNATURE_INVALID, Map.of(), Map.of("field", field), cause);
    }

    static String base64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    /** The challenge the authenticator signs for an intent: SHA-256 of the canonical intent string. */
    public static String intentChallenge(String intent) {
        return base64url(Digests.sha256(intent.getBytes(StandardCharsets.UTF_8)));
    }

    /** The prefix of a key fingerprint, as a JEV key's fingerprint spells it. */
    private static final String FINGERPRINT_PREFIX = "sha256:";

    /**
     * The fingerprint of a public key that a signed record carries in place of the key: {@code sha256:} and the lower-case hex of the SHA-256 of the key's bytes (the SPKI
     * the browser exposes, as base64url text). The key itself stays in the passkey registry of the settings database.
     */
    public static String fingerprint(String publicKey) {
        return FINGERPRINT_PREFIX + Digests.sha256Hex(bytes(publicKey, "publicKey"));
    }

    /** The short fingerprint the page shows next to a key, so a key planted by someone else stands out: the first 8 bytes of its SHA-256 as four groups of hex. */
    public static String displayFingerprint(String fingerprint) {
        String hex = fingerprint.substring(FINGERPRINT_PREFIX.length()).toUpperCase(Locale.ROOT);
        return IntStream.range(0, 4).mapToObj(group -> hex.substring(group * 4, group * 4 + 4)).collect(Collectors.joining("-"));
    }

    /** {@link #displayFingerprint} of the key's own fingerprint. */
    public static String keyFingerprint(String publicKey) {
        return displayFingerprint(fingerprint(publicKey));
    }

    /** Refuses a COSE algorithm this check does not verify. */
    static void requireAlgorithm(int alg) {
        if (!ALGORITHMS.containsKey(alg)) {
            throw algorithmUnsupported(Map.of("alg", alg));
        }
    }

    /** Imports a registered key; an unknown algorithm or a key of another type is refused, never guessed. */
    static PublicKey importKey(String publicKey, int alg) {
        Algorithm algorithm = ALGORITHMS.get(alg);
        if (algorithm == null) {
            throw algorithmUnsupported(Map.of("alg", alg));
        }
        byte[] der = bytes(publicKey, "publicKey");
        for (String type : KEY_TYPES) {
            PublicKey key;
            try {
                key = KeyFactory.getInstance(type).generatePublic(new X509EncodedKeySpec(der));
            } catch (GeneralSecurityException notThisType) {
                LOG.debug("The key is not a {} key", type, notThisType);
                continue;
            }
            if (!type.equals(algorithm.keyType())) {
                throw algorithmUnsupported(Map.of("alg", alg, "keyType", type));
            }
            if (key instanceof ECPublicKey ec && !isP256(ec)) {
                throw algorithmUnsupported(Map.of("alg", alg));
            }
            return key;
        }
        throw new InvalidInputException(InputCode.WEBAUTHN_PUBLIC_KEY_UNAVAILABLE);
    }

    private static InvalidInputException algorithmUnsupported(Map<String, Object> details) {
        return new InvalidInputException(InputCode.ADMIN_ALGORITHM_UNSUPPORTED, Map.of(), details);
    }

    private static boolean isP256(ECPublicKey key) {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec p256 = parameters.getParameterSpec(ECParameterSpec.class);
            return p256.getOrder().equals(key.getParams().getOrder()) && p256.getCurve().equals(key.getParams().getCurve());
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Whether this is the origin of the admin page: {@code http://localhost}, with a port or without. The text is read as a URI and written again from
     * its port, and only an origin that comes out the same is the page's (no path, user, query, other scheme or host, or spelling of the port). The port is not compared: the
     * page is on another port at each start, and the signed records of the log, which are checked again whenever it is read, name the port of the start that signed them. The
     * port a signature must carry is checked when it is made ({@code ContextLayerService.commit}), against the page that is listening.
     */
    public static boolean isAdminOrigin(String origin) {
        if (origin == null) {
            return false;
        }
        try {
            return new URI("http", null, RP_ID, new URI(origin).getPort(), null, null, null).toString().equals(origin);
        } catch (URISyntaxException invalid) {
            LOG.debug("The origin is not a URI", invalid);
            return false;
        }
    }

    /** The client data in the bytes the browser wrote; refused as an invalid signature when it is not a JSON object of the shape the browser writes. */
    private ClientData read(byte[] clientBytes) {
        ClientData client;
        try {
            client = mapper.readValue(clientBytes, ClientData.class);
        } catch (JacksonException invalid) {
            throw signatureInvalid("clientDataJSON", invalid);
        }
        if (client == null) {
            // The text {@code null} is JSON too, and binds to nothing.
            throw signatureInvalid("clientDataJSON");
        }
        return client;
    }

    /** The origin the browser wrote into an assertion's client data; the client data must be readable, or the assertion is refused as an invalid signature. */
    public String assertionOrigin(String clientDataJson) {
        return read(bytes(clientDataJson, "clientDataJSON")).origin();
    }

    /** Client data type, challenge, origin, RP ID hash, user presence and verification, then the signature. The sign counter decides nothing. */
    public void verify(String intent, Assertion assertion, String publicKey, int alg) {
        byte[] clientBytes = bytes(assertion.clientDataJson(), "clientDataJSON");
        ClientData client = read(clientBytes);
        if (client.type() != ClientDataType.GET) {
            throw signatureInvalid("type");
        }
        if (!intentChallenge(intent).equals(client.challenge())) {
            throw new InvalidInputException(InputCode.ADMIN_CHALLENGE_MISMATCH);
        }
        String origin = client.origin();
        if (!isAdminOrigin(origin)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("origin", origin == null ? null : origin.substring(0, Math.min(origin.length(), 100)));
            throw new GateRefusedException(GateCode.ADMIN_ORIGIN_REJECTED, Map.of(), details);
        }
        byte[] authenticatorData = bytes(assertion.authenticatorData(), "authenticatorData");
        if (authenticatorData.length < AUTHENTICATOR_DATA_MIN_LENGTH) {
            throw signatureInvalid("authenticatorData");
        }
        ByteBuffer data = ByteBuffer.wrap(authenticatorData);
        byte[] rpIdHash = new byte[RP_ID_HASH_LENGTH];
        data.get(rpIdHash);
        if (!MessageDigest.isEqual(rpIdHash, Digests.sha256(RP_ID.getBytes(StandardCharsets.UTF_8)))) {
            throw new InvalidInputException(InputCode.ADMIN_RPID_MISMATCH);
        }
        int flags = data.get() & 0xff;
        boolean present = (flags & FLAG_USER_PRESENT) != 0;
        boolean verified = (flags & FLAG_USER_VERIFIED) != 0;
        if (!present || !verified) {
            throw new InvalidInputException(InputCode.ADMIN_USER_VERIFICATION_MISSING, Map.of(), Map.of("userPresent", present, "userVerified", verified));
        }
        PublicKey key = importKey(publicKey, alg);
        byte[] signed = ByteBuffer.allocate(authenticatorData.length + 32).put(authenticatorData).put(Digests.sha256(clientBytes)).array();
        boolean valid;
        try {
            Signature verifier = Signature.getInstance(ALGORITHMS.get(alg).signature());
            verifier.initVerify(key);
            verifier.update(signed);
            valid = verifier.verify(bytes(assertion.signature(), "signature"));
        } catch (GeneralSecurityException rejected) {
            throw signatureInvalid("signature", rejected);
        }
        if (!valid) {
            throw signatureInvalid("signature");
        }
    }
}

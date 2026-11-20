// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.protocol.Json;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;

/**
 * D-0269: the key a person registers on the admin page is a {@link SecretValue} in the request record, with the input rules it had as a text (at least one character, at most 4096,
 * no control character; a violation names the field {@code secret}). The record's text, the violation and a JSON writer never show the value.
 */
class CommitRequestSecretTest {
    /** The messages are not made from templates (no expression language is needed): only the fields that fail are asked. */
    private static final Validator VALIDATOR = Validation.byDefaultProvider().configure().messageInterpolator(new ParameterMessageInterpolator()).buildValidatorFactory().getValidator();
    private static final String SECRET = "sk-page-secret-0269";

    private static AdminController.CommitRequest request(SecretValue secret) {
        AdminController.AssertionBody assertion = new AdminController.AssertionBody("credential", "authenticator", "client", "signature");
        return new AdminController.CommitRequest("intent", assertion, "request-0001", null, secret, null);
    }

    private static Set<String> fieldsOfViolations(AdminController.CommitRequest request) {
        return VALIDATOR.validate(request).stream().map(violation -> violation.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test
    void aSecretOfTheAllowedShapeAndNoSecretAreValid() {
        assertFalse(fieldsOfViolations(request(SecretValue.of(SECRET))).contains("secret"));
        assertFalse(fieldsOfViolations(request(SecretValue.of("a".repeat(4096)))).contains("secret"));
        assertFalse(fieldsOfViolations(request(null)).contains("secret"), "a change that carries no secret sends none");
    }

    @Test
    void aSecretThatIsTooLongOrHasAControlCharacterIsRefusedAtTheFieldSecret() {
        assertTrue(fieldsOfViolations(request(SecretValue.of("a".repeat(4097)))).contains("secret"));
        assertTrue(fieldsOfViolations(request(SecretValue.of("sk-\u0001"))).contains("secret"));
        assertTrue(fieldsOfViolations(request(SecretValue.of("sk-\n-line"))).contains("secret"));
        assertTrue(fieldsOfViolations(request(SecretValue.of("sk-\u007f"))).contains("secret"));
    }

    /** An empty key is a violation of the field {@code secret} (the validation error that names the field), as the text it was before D-0269, and not a failure of reading the body. */
    @Test
    void anEmptySecretIsReadAndRefusedAtTheFieldSecret() throws Exception {
        String body = "{\"intent\":\"i\",\"assertion\":{\"credentialId\":\"c\",\"authenticatorData\":\"a\",\"clientDataJSON\":\"d\",\"signature\":\"s\"},\"requestId\":\"request-0001\",\"secret\":\"\"}";
        AdminController.CommitRequest read = Json.MAPPER.readValue(body, AdminController.CommitRequest.class);
        assertEquals(0, read.secret().length());
        assertTrue(fieldsOfViolations(read).contains("secret"));
    }

    @Test
    void theViolationAndTheRequestTextHaveNoValue() {
        AdminController.CommitRequest request = request(SecretValue.of("sk-\u0001-" + SECRET));
        for (ConstraintViolation<AdminController.CommitRequest> violation : VALIDATOR.validate(request)) {
            assertFalse(String.valueOf(violation.getInvalidValue()).contains("page-secret"), violation.getPropertyPath().toString());
            assertFalse(violation.getMessage().contains("page-secret"));
        }
        assertFalse(request.toString().contains("page-secret"));
        assertTrue(request.toString().contains("SecretValue[redacted]"));
    }

    @Test
    void theRequestIsReadFromTheJsonThePageSendsAndIsNotWrittenBack() throws Exception {
        String body = "{\"intent\":\"i\",\"assertion\":{\"credentialId\":\"c\",\"authenticatorData\":\"a\",\"clientDataJSON\":\"d\",\"signature\":\"s\"},\"requestId\":\"request-0001\",\"secret\":\"" + SECRET + "\"}";
        AdminController.CommitRequest read = Json.MAPPER.readValue(body, AdminController.CommitRequest.class);
        assertEquals(SecretValue.of(SECRET), read.secret());
        assertThrows(JacksonException.class, () -> Json.MAPPER.writeValueAsString(read));
    }
}

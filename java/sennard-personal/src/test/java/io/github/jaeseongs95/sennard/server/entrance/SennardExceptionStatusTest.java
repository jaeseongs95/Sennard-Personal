// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.entrance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import io.github.jaeseongs95.sennard.domain.error.BindingCode;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.GateCode;
import io.github.jaeseongs95.sennard.domain.error.GateRefusedException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.NotFoundCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;

/**
 * The HTTP status of each reason, by the one switch of {@code SennardExceptionHandler}: the category's, but for the admin page's gates, which were 403 as
 * {@code AdminError} and stay so. The switch has no {@code default} (rule t of the architecture checks), so a category or a gate reason added to the hierarchy does not compile
 * until it has a status. Cases: every constant of every code enum, built as its category's exception, is answered with the status in the table.
 *
 * <pre>
 * h1  input        every InputCode (the admin ones too) is 400
 * h2  not found    every NotFoundCode is 404
 * h3  conflict     every ConflictCode is 409
 * h4  gate         GATE_FAILED is 422; the four ADMIN_* gates (origin, key not registered, unknown, revoked) are 403
 * h5  binding      every BindingCode is 401
 * h6  integrity    every IntegrityCode is 422
 * h7  unavailable  every UnavailableCode is 503
 * </pre>
 */
class SennardExceptionStatusTest {
    @Test
    void everyReasonIsAnsweredWithItsCategorysStatusButTheAdminGates() {
        for (InputCode code : InputCode.values()) {
            assertEquals(HttpStatus.BAD_REQUEST, SennardExceptionHandler.status(new InvalidInputException(code)), code.name());
        }
        for (NotFoundCode code : NotFoundCode.values()) {
            assertEquals(HttpStatus.NOT_FOUND, SennardExceptionHandler.status(new NotFoundException(code)), code.name());
        }
        for (ConflictCode code : ConflictCode.values()) {
            assertEquals(HttpStatus.CONFLICT, SennardExceptionHandler.status(new ConflictException(code)), code.name());
        }
        for (GateCode code : GateCode.values()) {
            // The admin page's refusals of who may sign a change, and from where, are 403; the gate of the product and the commands' refusals (sennard-personal's CLI) are 422.
            boolean adminPage = code.name().startsWith("ADMIN_") && !code.name().startsWith("ADMIN_RESET_");
            assertEquals(adminPage ? HttpStatus.FORBIDDEN : HttpStatus.UNPROCESSABLE_CONTENT, SennardExceptionHandler.status(new GateRefusedException(code)), code.name());
        }
        for (BindingCode code : BindingCode.values()) {
            assertEquals(HttpStatus.UNAUTHORIZED, SennardExceptionHandler.status(new BindingException(code)), code.name());
        }
        for (IntegrityCode code : IntegrityCode.values()) {
            assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, SennardExceptionHandler.status(new IntegrityException(code)), code.name());
        }
        for (UnavailableCode code : UnavailableCode.values()) {
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, SennardExceptionHandler.status(new UnavailableException(code)), code.name());
        }
    }
}

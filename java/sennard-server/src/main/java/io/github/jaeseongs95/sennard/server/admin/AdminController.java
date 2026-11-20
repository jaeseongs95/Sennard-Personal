// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

import org.springframework.context.i18n.SimpleLocaleContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import io.github.jaeseongs95.sennard.domain.error.NotFoundCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.server.context.AdminChange;
import io.github.jaeseongs95.sennard.server.context.AdminWebAuthn;
import io.github.jaeseongs95.sennard.server.context.ContextLayerService;
import io.github.jaeseongs95.sennard.server.context.PreLinkAdminService;
import io.github.jaeseongs95.sennard.server.web.AdminPageAddress;
import io.github.jaeseongs95.sennard.server.web.RequestLocale;
import io.github.jaeseongs95.sennard.server.web.SennardLocaleResolver;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * The local admin page. Viewing needs no passkey; every change is a record the user's passkey signs and the service verifies. {@link AdminPortFilter} keeps these routes on
 * their own port and checks the page's origin first, and the guard filter limits the body. A request is a record whose constraints are the page's input rules
 * ({@code @Valid}); a refusal is an RFC 9457 problem whose {@code code} the page has a sentence for, made by the application's one error advice, not here.
 */
@RestController
public class AdminController {
    private static final int INTENT_MAX_LENGTH = 64 * 1024;
    private static final int SECRET_MAX_LENGTH = 4096;
    private static final String REQUEST_ID = "^[A-Za-z0-9_-]{8,100}$";
    private static final String BASE64_URL = "^[A-Za-z0-9_-]+$";
    private static final Accepted ACCEPTED = new Accepted(true);

    /**
     * @param change the change to be signed, checked by the signed log's own rules
     * @param bootstrap whether this registers the first key, which signs its own registration
     * @param baseCheck whether the change was made on top of {@code baseHash}: the page sends it with a base it read, and a null {@code baseHash} then means "the chain was empty"
     */
    public record IntentRequest(@NotNull JsonNode change, Boolean bootstrap, Boolean baseCheck, @Size(max = 200) String baseHash) {
        /** Jackson 3 refuses a missing or null primitive, so the two flags are boxed and an absent one is false. */
        public IntentRequest {
            bootstrap = Boolean.TRUE.equals(bootstrap);
            baseCheck = Boolean.TRUE.equals(baseCheck);
        }
    }

    /** What the passkey made when it signed (WebAuthn's {@code AuthenticatorAssertionResponse}, Base64url). */
    public record AssertionBody(@Size(min = 1, max = 1400) @NotNull String credentialId, @Size(min = 1, max = 4000) @NotNull String authenticatorData,
            @Size(min = 1, max = 8000) @NotNull String clientDataJSON, @Size(min = 1, max = 2000) @NotNull String signature) {
    }

    /**
     * @param secret a JEV key's value travels only with its signed registration; it is sealed and never sent back
     * @param proposalId the agent's proposal this signed change answers, when it does
     * @param publicKey a passkey's public key (the SPKI as base64url text) travels only with its signed registration: the signed change names its fingerprint, and the key is kept
     *     in the passkey registry of the settings database, never in the business database
     */
    public record CommitRequest(@Size(min = 1, max = INTENT_MAX_LENGTH) @NotNull String intent, @Valid @NotNull AssertionBody assertion,
            @Pattern(regexp = REQUEST_ID) @NotNull String requestId, @Size(min = 1, max = 100) String proposalId,
            @SecretText(max = SECRET_MAX_LENGTH) SecretValue secret,
            @Size(min = 1, max = AdminChange.PUBLIC_KEY_MAX_LENGTH) @Pattern(regexp = BASE64_URL) String publicKey) {
    }

    /** A rejection carries no field; the record is what makes the body a JSON object. */
    public record RejectRequest() {
    }

    public record Accepted(boolean ok) {
    }

    /** The language the user chose: one the page speaks, {@code en} or {@code ko}; any other value is refused with 400. */
    public record LanguageRequest(@NotNull @Pattern(regexp = "^(en|ko)$") String language) {
    }

    public record LanguageChosen(String language) {
    }

    public record RecordView(long seq, String target, String recordHash, String createdAt) {
        static RecordView of(ContextLayerStore.LogRecord record) {
            return new RecordView(record.seq(), record.target(), record.recordHash(), record.createdAt());
        }
    }

    /** The answer of {@code GET /api/request/:id}: the record the request signed, or {@code "record": null} on the wire when no record has this request id (the page reads that null). */
    public record RecordLookup(RecordView record) {
        static RecordLookup none() {
            return new RecordLookup(null);
        }
    }

    public record Committed(RecordView record, boolean duplicate) {
    }

    private final io.github.jaeseongs95.sennard.app.attachment.AttachmentFileStore attachmentFiles;
    private final io.github.jaeseongs95.sennard.server.context.AdminLogVerifier verifier;
    private final ContextLayerService service;
    private final PreLinkAdminService preLink;
    private final AdminPageData data;
    private final AdminPageAddress address;
    private final String page;
    private final CspNonce nonces;
    private final AdminTexts texts;
    private final SennardLocaleResolver locales;

    public AdminController(ContextLayerService service, PreLinkAdminService preLink, AdminPageData data, AdminPageAddress address, CspNonce nonces, AdminTexts texts, SennardLocaleResolver locales, io.github.jaeseongs95.sennard.app.attachment.AttachmentFileStore attachmentFiles, io.github.jaeseongs95.sennard.server.context.AdminLogVerifier verifier) throws IOException {
        this.verifier = verifier;
        this.attachmentFiles = attachmentFiles;
        this.service = service;
        this.preLink = preLink;
        this.data = data;
        this.nonces = nonces;
        this.address = address;
        this.texts = texts;
        this.locales = locales;
        try (InputStream in = new ClassPathResource("admin/admin-page.html").getInputStream()) {
            this.page = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @GetMapping(path = "/", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page() {
        String nonce = nonces.next();
        // The page is written in the request's language: what the HTML itself carries (language, title, the line shown before the script runs) is rendered here, and
        // every other sentence is in the catalog the page reads (AdminTexts).
        Locale locale = RequestLocale.current();
        String body = page.replace("__PAGE_LANGUAGE__", HtmlUtils.htmlEscape(texts.language(locale)))
                .replace("__PAGE_TITLE__", HtmlUtils.htmlEscape(texts.text("frame.title", locale)))
                .replace("__PAGE_NAV_LABEL__", HtmlUtils.htmlEscape(texts.text("frame.navLabel", locale)))
                .replace("__PAGE_LOADING__", HtmlUtils.htmlEscape(texts.text("frame.loading", locale)))
                .replace("__PAGE_NOSCRIPT__", HtmlUtils.htmlEscape(texts.text("frame.noScript", locale)))
                .replace("__PAGE_SOURCE_CODE__", HtmlUtils.htmlEscape(texts.text("frame.sourceCode", locale)))
                .replace("__PAGE_TEXTS__", texts.patternsJson(locale))
                .replace("__CSP_NONCE__", nonce);
        return ResponseEntity.ok().contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .header("Content-Security-Policy", "default-src 'none'; script-src 'nonce-" + nonce + "'; style-src 'nonce-" + nonce + "'; connect-src 'self'; img-src 'self' data:; "
                        + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
                .header("Vary", "Accept-Language, Cookie")
                .body(body);
    }

    /**
     * The user chose the language of the page: it is remembered in a cookie of the page ({@link SennardLocaleResolver}), which the next request of the page follows before
     * Accept-Language. Nothing else is kept and nothing is signed: a language is not a change of the settings.
     */
    @PostMapping("/api/language")
    public LanguageChosen language(@RequestBody @Valid LanguageRequest request, HttpServletRequest http, HttpServletResponse response) {
        locales.setLocaleContext(http, response, new SimpleLocaleContext(Locale.forLanguageTag(request.language())));
        return new LanguageChosen(request.language());
    }

    @GetMapping("/api/state")
    public AdminPageData.State state() {
        return data.state();
    }

    @GetMapping("/api/skill-usage")
    public AdminPageData.SkillUsage skillUsage(@RequestParam(required = false) @NotNull @SkillUsageDays Integer days) {
        return data.skillUsage(days);
    }

    /** The workflow statistics: the same periods as the skill statistics. */
    @GetMapping("/api/workflow-stats")
    public WorkflowStatsView.Stats workflowStats(@RequestParam(required = false) @NotNull @SkillUsageDays Integer days) {
        return data.workflowStats(days);
    }

    @GetMapping("/api/request/{id}")
    public RecordLookup request(@PathVariable("id") @Pattern(regexp = REQUEST_ID) String requestId) {
        Optional<ContextLayerStore.LogRecord> record = data.beforeLink() ? preLink.recordByRequest(requestId) : service.recordByRequest(requestId);
        return record.map(found -> new RecordLookup(RecordView.of(found))).orElseGet(RecordLookup::none);
    }

    @PostMapping("/api/intent")
    public ContextLayerService.PreparedIntent intent(@RequestBody @Valid IntentRequest request) {
        // Before the business database is ready (and until it first is) the page signs into the settings database; after that into the business log.
        var prepared = data.beforeLink() ? preLink.prepareIntent(request.change(), request.bootstrap(), request.baseCheck(), request.baseHash())
                : service.prepareIntent(request.change(), request.bootstrap(), request.baseCheck(), request.baseHash());
        if (verifier.bindChange(request.change()) instanceof AdminChange.AttachmentStorageSet set) {
            attachmentFiles.prepareDirectory(java.nio.file.Path.of(set.settings().directory()));
        }
        return prepared;
    }

    @PostMapping("/api/commit")
    public Committed commit(@RequestBody @Valid CommitRequest request) {
        AssertionBody assertion = request.assertion();
        ContextLayerService.CommitInput input = new ContextLayerService.CommitInput(request.intent(),
                new AdminWebAuthn.Assertion(assertion.credentialId(), assertion.authenticatorData(), assertion.clientDataJSON(), assertion.signature()), request.requestId(),
                request.proposalId(), address.origin(), request.secret(), request.publicKey());
        try {
            ContextLayerService.CommitResult result = data.beforeLink() ? preLink.commit(input) : service.commit(input);
            return new Committed(RecordView.of(result.record()), result.duplicate());
        } finally {
            // The key a signed registration carries is sealed inside the commit and nothing keeps it: the request's own bytes are overwritten when the call is over.
            if (request.secret() != null) {
                request.secret().destroy();
            }
        }
    }

    @PostMapping("/api/proposals/{id}/reject")
    public Accepted rejectProposal(@PathVariable("id") String proposalId, @RequestBody @Valid RejectRequest request) {
        service.rejectProposal(proposalId);
        return ACCEPTED;
    }

    @PostMapping("/api/root-approvals/{id}/reject")
    public Accepted rejectRootApproval(@PathVariable("id") String approvalId, @RequestBody @Valid RejectRequest request) {
        if (!data.rejectRootApproval(approvalId)) {
            throw new NotFoundException(NotFoundCode.ADMIN_PROPOSAL_NOT_FOUND);
        }
        return ACCEPTED;
    }
}

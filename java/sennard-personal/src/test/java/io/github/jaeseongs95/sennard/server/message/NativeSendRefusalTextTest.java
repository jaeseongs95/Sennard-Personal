// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import io.github.jaeseongs95.sennard.app.message.CatalogMessageSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The refusal of a native send that leaves the delegation, as the catalogs make it (the sentence {@code MessagingHookHandler} renders through {@link Messages}). What a hook says is
 * read by the model, so it is in the model's locale whatever language the request being answered carries; the Korean sentence is the person's, and says the same things.
 */
class NativeSendRefusalTextTest {
    private static final Messages MESSAGES = new Messages(new MessageSourceAccessor(new CatalogMessageSource(new PathMatchingResourcePatternResolver(), JsonMapper.builder().build())));

    private static MessageRef refusal(MessageRef to) {
        return MessageRef.of("peer.send.refused", "tool", "SendMessage", "to", to);
    }

    private static MessageRef toRecipient(String recipient) {
        return MessageRef.of("peer.send.toRecipient", "recipient", recipient);
    }

    @Test
    void theRefusalNamesTheToolTheRecipientAndWhatToDoInstead() {
        String text = MESSAGES.render(refusal(toRecipient("a0123456789abcdef")));

        assertTrue(text.contains("SendMessage call to a0123456789abcdef was blocked"), text);
        assertTrue(text.indexOf("list_session_status") < text.indexOf("prepare_session_message"), text);
        assertTrue(text.indexOf("prepare_session_message") < text.indexOf("send_session_message"), text);
        assertTrue(text.contains("targetHost") && text.contains("targetSessionId"), text);
        assertTrue(text.contains("id the host gave it or the name you gave it"), "the way to reach a subagent this session started: " + text);
    }

    private static MessageRef unaddressed() {
        return MessageRef.of("peer.send.refusedUnaddressed", "tool", "SendMessage");
    }

    /**
     * A call that names no recipient (a host tool with no recipient field, or a call that left it out) is refused without any invitation to send it again with the same tool: the
     * sentence says only what to use instead.
     */
    @Test
    void aCallThatNamesNoRecipientIsToldToUseSennardPeerMessagingAndNotToSendItAgain() {
        String text = MESSAGES.render(unaddressed());

        assertTrue(text.contains("SendMessage call was blocked, because its recipient cannot be read from this call"), text);
        assertFalse(text.contains("does not name"), text);
        assertTrue(text.contains("Do not send it with this tool again"), text);
        assertTrue(text.indexOf("list_session_status") < text.indexOf("prepare_session_message"), text);
        assertTrue(text.indexOf("prepare_session_message") < text.indexOf("send_session_message"), text);
        assertFalse(text.contains("send again") || text.contains("name you gave it") || text.contains("id the host gave it"), text);
    }

    @Test
    void aHookTextIsInTheModelsLocaleWhateverLanguageTheRequestCarries() {
        Locale before = LocaleContextHolder.getLocale();
        LocaleContextHolder.setLocale(Locale.KOREAN);
        try {
            String text = MESSAGES.render(refusal(toRecipient("researcher")));
            assertTrue(text.startsWith("Nothing was sent: "), text);
            assertEquals(MESSAGES.render(refusal(toRecipient("researcher")), "en"), text);
            assertFalse(text.contains("아무것도"), text);
        } finally {
            LocaleContextHolder.setLocale(before);
        }
    }

    @Test
    void theKoreanSentenceSaysTheSameThings() {
        String text = MESSAGES.render(refusal(toRecipient("researcher")), "ko");

        assertTrue(text.startsWith("아무것도 보내지 않았다."), text);
        assertTrue(text.contains("SendMessage 호출(researcher에게)"), text);
        assertTrue(text.indexOf("list_session_status") < text.indexOf("prepare_session_message"), text);
        assertTrue(text.indexOf("prepare_session_message") < text.indexOf("send_session_message"), text);
        assertTrue(text.contains("targetHost") && text.contains("targetSessionId"), text);
        assertTrue(text.contains("호스트가 준 id나 시작할 때 붙인 이름"), text);
    }

    @Test
    void theKoreanSentenceForACallWithoutARecipientAlsoSaysNotToSendItAgain() {
        String text = MESSAGES.render(unaddressed(), "ko");

        assertTrue(text.startsWith("아무것도 보내지 않았다. SendMessage 호출을 막았다."), text);
        assertTrue(text.contains("받는 쪽을 읽을 수 없어") && !text.contains("말하지 않아") && text.contains("이 도구로 다시 보내지 마라"), text);
        assertTrue(text.indexOf("list_session_status") < text.indexOf("prepare_session_message"), text);
        assertTrue(text.indexOf("prepare_session_message") < text.indexOf("send_session_message"), text);
        assertFalse(text.contains("다시 보내라") || text.contains("시작할 때 붙인 이름"), text);
    }
}

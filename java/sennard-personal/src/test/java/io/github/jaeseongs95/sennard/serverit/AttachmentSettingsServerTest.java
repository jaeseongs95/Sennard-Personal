// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.jaeseongs95.sennard.app.attachment.*;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import tools.jackson.databind.node.ObjectNode;

/** Signed settings over real HTTP and PostgreSQL; encrypted bodies on the real local filesystem. */
@Tag("business-db")
class AttachmentSettingsServerTest {
    @Test
    void signedFolderAndLimitsPreserveOldAndPartiallyWrittenBodies(@TempDir Path temp) throws Exception {
        try (var server = ToolServerSupport.start()) {
            var owner = server.owner();
            var settings = server.bean(AttachmentSettings.class);
            var initial = settings.current();
            var uploads = server.bean(AttachmentUploads.class);
            var store = server.bean(AttachmentFileStore.class);
            byte[] data = "stored before changing directory".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String hash = "sha256:" + Digests.sha256Hex(data);
            var declared = new AttachmentService.BeginFile("test.txt", "text/plain", data.length, hash);
            var before = uploads.begin("test-owner", new AttachmentService.BeginRequest("begin-"+UUID.randomUUID(), List.of(declared), null));
            uploads.chunk(before.files().getFirst().uploadId(), 0, data, hash);
            var kept = uploads.finish(before.attachmentId());
            var pending = uploads.begin("test-owner", new AttachmentService.BeginRequest("begin-"+UUID.randomUUID(), List.of(declared), null));
            byte[] head = java.util.Arrays.copyOfRange(data, 0, 8);
            byte[] tail = java.util.Arrays.copyOfRange(data, 8, data.length);
            uploads.chunk(pending.files().getFirst().uploadId(), 0, head, "sha256:" + Digests.sha256Hex(head));
            Path next = temp.resolve("new attachments").toAbsolutePath();
            ObjectNode value = ToolServerSupport.MAPPER.createObjectNode().put("directory", next.toString()).put("maxFileBytes", 1024)
                    .put("stagingBudgetBytes", 4096).put("attachmentBudgetBytes", 8192).put("maxOpenPerOwner", 4).put("diskMarginBytes", 0);
            ObjectNode change = ToolServerSupport.MAPPER.createObjectNode().put("op", "ATTACHMENT_STORAGE_SET");
            change.set("settings", value);
            for (ObjectNode invalid : List.of(
                    value.deepCopy().put("directory", "relative/attachments"),
                    value.deepCopy().put("maxFileBytes", 4097),
                    value.deepCopy().put("stagingBudgetBytes", 8193),
                    value.deepCopy().put("maxFileBytes", 0),
                    value.deepCopy().put("maxFileBytes", 1.5),
                    value.deepCopy().put("maxOpenPerOwner", 0),
                    value.deepCopy().put("diskMarginBytes", -1),
                    value.deepCopy().put("maxFileBytes", 9007199254740992L))) {
                ObjectNode bad = ToolServerSupport.MAPPER.createObjectNode().put("op", "ATTACHMENT_STORAGE_SET");
                bad.set("settings", invalid);
                ObjectNode request = ToolServerSupport.MAPPER.createObjectNode().put("bootstrap", false);
                request.set("change", bad);
                var refusal = server.post("/api/intent", request);
                assertEquals(400, refusal.status(), refusal.text());
                assertEquals("ADMIN_CHANGE_INVALID", SessionServerSupport.json(refusal).path("code").asString(), refusal.text());
                assertEquals(initial, settings.current());
            }
            String intent = server.prepare(change, false).path("intent").asString();
            ObjectNode invalidSignature = ToolServerSupport.MAPPER.createObjectNode().put("credentialId", owner.credentialId)
                    .put("authenticatorData", "AA").put("clientDataJSON", "AA").put("signature", "AA");
            var unsigned = server.commit(intent, invalidSignature, UUID.randomUUID().toString(), null, null);
            assertEquals(400, unsigned.status(), unsigned.text());
            assertEquals("ADMIN_SIGNATURE_INVALID", SessionServerSupport.json(unsigned).path("code").asString());
            assertEquals(initial, settings.current());
            String stale = server.prepare(change, false).path("intent").asString();
            var committed = server.commit(intent, owner.assertion(intent, server.adminOrigin), UUID.randomUUID().toString(), null, null);
            assertEquals(200, committed.status(), committed.text());
            assertEquals(next, settings.current().directory());
            assertEquals(1024, settings.current().maxFileBytes());
            assertTrue(settings.current().directories().contains(initial.directory()));
            assertArrayEquals(data, uploads.read(kept.attachmentId(), 0, 0, data.length).page().data());
            uploads.chunk(pending.files().getFirst().uploadId(), 1, tail, "sha256:" + Digests.sha256Hex(tail));
            uploads.finish(pending.attachmentId());
            assertArrayEquals(data, uploads.read(pending.attachmentId(), 0, 0, data.length).page().data());
            assertEquals(409, server.commit(stale, owner.assertion(stale, server.adminOrigin), UUID.randomUUID().toString(), null, null).status());
            assertThrows(SennardException.class, () -> uploads.begin("test-owner", new AttachmentService.BeginRequest("begin-"+UUID.randomUUID(),
                    List.of(new AttachmentService.BeginFile("too-large", "application/octet-stream", 1025, hash)), null)));
            assertThrows(SennardException.class, () -> store.prepareDirectory(server.bean(AttachmentProperties.class).keyDirectory()));
            var after = uploads.begin("test-owner", new AttachmentService.BeginRequest("begin-"+UUID.randomUUID(), List.of(declared), null));
            uploads.chunk(after.files().getFirst().uploadId(), 0, data, hash);
            uploads.finish(after.attachmentId());
            assertArrayEquals(data, uploads.read(after.attachmentId(), 0, 0, data.length).page().data());
            assertTrue(server.state().path("settings").path("attachmentStorage").path("signed").asBoolean());
            byte[] exact = new byte[1024];
            String exactHash = "sha256:" + Digests.sha256Hex(exact);
            var atLimit = uploads.begin("test-owner", new AttachmentService.BeginRequest("exact-" + UUID.randomUUID(),
                    List.of(new AttachmentService.BeginFile("exact", "application/octet-stream", 1024, exactHash)), null));
            uploads.chunk(atLimit.files().getFirst().uploadId(), 0, exact, exactHash);
            uploads.finish(atLimit.attachmentId());
            assertArrayEquals(exact, uploads.read(atLimit.attachmentId(), 0, 0, 1024).page().data());
            try (var reader = ToolServerSupport.start()) {
                reader.adoptPasskeysOf(server);
                var persisted = reader.bean(AttachmentSettings.class).current();
                assertEquals(next, persisted.directory());
                assertEquals(1024, persisted.maxFileBytes());
                assertTrue(reader.state().path("settings").path("attachmentStorage").path("signed").asBoolean());
            }
        }
    }
}

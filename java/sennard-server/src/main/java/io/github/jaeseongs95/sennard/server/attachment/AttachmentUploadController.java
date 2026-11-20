// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.attachment;

import java.io.IOException;
import java.util.HexFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import io.github.jaeseongs95.sennard.app.attachment.ArtifactErrors;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentService;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentUploads;
import io.github.jaeseongs95.sennard.domain.Digests;

/** Raw file transport shared by every host. Multipart parsing and temporary-file cleanup belong to Spring/Servlet. */
@RestController
public class AttachmentUploadController {
    public static final String PATH = "/attachments/upload";
    public static final String UPLOAD_HEADER = "X-Sennard-Upload-Id";
    private final AttachmentUploads uploads;
    public AttachmentUploadController(AttachmentUploads uploads) { this.uploads = uploads; }

    @PostMapping(path = PATH, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentService.UploadStatus upload(@RequestHeader(UPLOAD_HEADER) String uploadId, @RequestPart("file") MultipartFile file) {
        AttachmentService.UploadStatus status = uploads.uploadStatus(uploadId);
        if (file.getSize() != status.size()) throw ArtifactErrors.invalidArgument("file", "size differs from the declared file");
        try {
            String digest;
            try (var input = file.getInputStream()) { digest = HexFormat.of().formatHex(Digests.sha256(input)); }
            if (!status.sha256().equals("sha256:" + digest)) throw ArtifactErrors.hashMismatch("multipart-file");
            try (var input = file.getInputStream()) { return uploads.transfer(uploadId, input, file.getSize()); }
        } catch (IOException failure) { throw ArtifactErrors.storageIo(failure); }
    }
}

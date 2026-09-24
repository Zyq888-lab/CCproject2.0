package com.jifeng.assessment.score;

import com.jifeng.assessment.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceControllerTest {
    @TempDir Path tempDir;
    private EvidenceController controller;

    @BeforeEach
    void setUp() {
        controller = new EvidenceController();
        ReflectionTestUtils.setField(controller, "uploadDir", tempDir.toString());
    }

    @Test
    void downloadReturnsStoredPdfInlineWithNosniff() throws IOException {
        Files.write(tempDir.resolve("proof.pdf"), new byte[] {1, 2, 3});

        ResponseEntity<Resource> response = controller.download("proof.pdf");

        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("inline;"));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertNotNull(response.getBody());
        assertArrayEquals(new byte[] {1, 2, 3}, response.getBody().getInputStream().readAllBytes());
    }

    @Test
    void downloadForcesUnknownTypeAsAttachment() throws IOException {
        Files.writeString(tempDir.resolve("proof.txt"), "evidence");

        ResponseEntity<Resource> response = controller.download("proof.txt");

        assertEquals(MediaType.APPLICATION_OCTET_STREAM, response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment;"));
    }

    @Test
    void downloadRejectsTraversalAndMissingFile() {
        BusinessException traversal = assertThrows(BusinessException.class,
                () -> controller.download("../secret.txt"));
        assertEquals(400, traversal.getCode());

        BusinessException missing = assertThrows(BusinessException.class,
                () -> controller.download("missing.pdf"));
        assertEquals(404, missing.getCode());
    }
}

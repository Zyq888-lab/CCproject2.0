// 模块用途：凭证下载 REST 接口——按文件名从本地上传目录读取并返回文件字节
// 依赖文件：BusinessException.java
// 修改注意：文件名仅允许 UUID 生成的裸名（无 / \ ..），防路径穿越；挂载于 /api/v1 命名空间，
//   经 vite/nginx 的 /api 反代即可访问；下载前按评分所属任务校验考核人、项目负责人或已发布结果的本人权限
package com.jifeng.assessment.score;

import com.jifeng.assessment.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class EvidenceController {

    private final EvidenceAccessService evidenceAccessService;

    // 上传目录与 ScoreService.uploadEvidence 同源（可被 app.upload-dir 覆盖）
    @Value("${app.upload-dir:./uploads/evidence}")
    private String uploadDir;

    // 功能：按文件名下载凭证——校验文件名合法性，返回文件字节 + 按扩展名推断 Content-Type；
    //   非图片/PDF 一律 attachment（防上传 HTML/SVG 触发 XSS），图片/PDF inline 便于直接查看
    @GetMapping("/evidence/{filename:.+}")
    public ResponseEntity<Resource> download(@PathVariable String filename) {
        if (filename == null || !filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,200}")
                || filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            throw new BusinessException(400, "非法的凭证文件名");
        }
        evidenceAccessService.assertCanDownload(filename);
        Path file = Paths.get(uploadDir).resolve(filename).normalize();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException(404, "凭证文件不存在或已被删除");
        }

        Resource resource = new FileSystemResource(file);
        String lower = filename.toLowerCase();
        MediaType mediaType;
        boolean inline;
        if (lower.endsWith(".png")) {
            mediaType = MediaType.IMAGE_PNG;
            inline = true;
        } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            mediaType = MediaType.IMAGE_JPEG;
            inline = true;
        } else if (lower.endsWith(".gif")) {
            mediaType = MediaType.IMAGE_GIF;
            inline = true;
        } else if (lower.endsWith(".pdf")) {
            mediaType = MediaType.APPLICATION_PDF;
            inline = true;
        } else {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
            inline = false;
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        (inline ? "inline" : "attachment") + "; filename=\"" + filename + "\"")
                .body(resource);
    }
}

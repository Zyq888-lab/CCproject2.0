// 模块用途：批量导入REST接口——Excel预览 + 确认执行 + 导入报告
// 依赖文件：ImportService.java, ImportResultDTO.java, BaseController.java
// 修改注意：preview和execute都接受同一份Excel，preview不写库，execute逐行写库
package com.jifeng.assessment.importer;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class ImportController extends BaseController {

    private final ImportService importService;

    // 功能：预览导入——读取Excel前10行，返回表头映射和数据样本，不写入数据库
    @PostMapping("/api/v1/import/employees/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ImportResultDTO.PreviewResult> preview(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return fail("请上传文件");
        }
        return ok(importService.preview(file));
    }

    // 功能：执行导入——解析Excel全部行，逐行校验后写入数据库，返回成功/失败明细
    @PostMapping("/api/v1/import/employees/execute")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ImportResultDTO.ExecuteResult> execute(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return fail("请上传文件");
        }
        return ok(importService.execute(file));
    }
}

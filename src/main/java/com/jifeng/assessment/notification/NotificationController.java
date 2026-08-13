// 模块用途：通知REST接口——查询通知列表、未读计数、标记已读
// 依赖文件：NotificationService.java, BaseController.java
// 修改注意：未读计数用于仪表盘红点，标记已读为 PUT 幂等操作
package com.jifeng.assessment.notification;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import com.jifeng.assessment.common.PageQuery;
import com.jifeng.assessment.common.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController extends BaseController {

    private final NotificationService notificationService;

    // 功能：分页查询通知列表——按接收人+已读/未读筛选
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'PD', '评估人', '员工')")
    public ApiResponse<PageResult<Notification>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String recipientId,
            @RequestParam(required = false) Boolean isRead) {
        PageQuery query = new PageQuery();
        query.setPage(page);
        query.setSize(size);
        return ok(notificationService.listNotifications(query, recipientId, isRead));
    }

    // 功能：未读计数——仪表盘红点数字（不传 recipientId 时自动解析当前用户）
    @GetMapping("/unread-count")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'PD', '评估人', '员工')")
    public ApiResponse<Long> unreadCount(@RequestParam(required = false) String recipientId) {
        if (recipientId == null || recipientId.isEmpty()) {
            return ok(notificationService.unreadCountForCurrentUser());
        }
        return ok(notificationService.unreadCount(recipientId));
    }

    // 功能：标记已读——点击某条通知后调用
    @PutMapping("/{id}/read")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'PD', '评估人', '员工')")
    public ApiResponse<Notification> markRead(@PathVariable Long id) {
        return ok(notificationService.markRead(id));
    }
}

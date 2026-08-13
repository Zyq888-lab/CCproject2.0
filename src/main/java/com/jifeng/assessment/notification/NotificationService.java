// 模块用途：通知业务逻辑——@Async 批量发送、未读计数、标记已读
// 依赖文件：NotificationMapper.java, Notification.java, BaseService.java
// 修改注意：notifyBatch 标记 @Async 移出调用方事务；失败仅记录日志不影响主流程
package com.jifeng.assessment.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BaseService;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.common.PageQuery;
import com.jifeng.assessment.common.PageResult;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService extends BaseService<NotificationMapper, Notification> {

    private final SysUserMapper sysUserMapper;

    // 功能：异步批量发送通知——移出调用方事务，失败仅记录日志不影响主流程
    @Async
    public void notifyBatch(List<Notification> notifications) {
        if (notifications == null || notifications.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            for (Notification n : notifications) {
                if (n.getCreatedAt() == null) {
                    n.setCreatedAt(now);
                }
                if (n.getIsRead() == null) {
                    n.setIsRead(false);
                }
                baseMapper.insert(n);
            }
            log.info("批量通知发送成功，共 {} 条", notifications.size());
        } catch (Exception e) {
            log.error("批量通知发送失败，共 {} 条: {}", notifications.size(), e.getMessage(), e);
        }
    }

    // 功能：分页查询通知列表——按接收人筛选，支持按已读/未读过滤
    public PageResult<Notification> listNotifications(PageQuery query, String recipientId, Boolean isRead) {
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<>();
        if (recipientId != null && !recipientId.isEmpty()) {
            wrapper.eq(Notification::getRecipientId, recipientId);
        }
        if (isRead != null) {
            wrapper.eq(Notification::getIsRead, isRead);
        }
        wrapper.orderByDesc(Notification::getId);
        return selectPage(query, wrapper);
    }

    // 功能：未读计数——过滤 is_read=false，用于仪表盘红点
    public long unreadCount(String recipientId) {
        return baseMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientId, recipientId)
                .eq(Notification::getIsRead, false));
    }

    // 功能：当前用户未读计数——从 SecurityContext 反查 userId，供 AppLayout 顶部红点使用
    public long unreadCountForCurrentUser() {
        String userId = getCurrentUserId();
        if (userId == null) {
            return 0;
        }
        return unreadCount(userId);
    }

    // 功能：从 SecurityContext 用户名反查当前用户 userId
    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, auth.getName()));
        return user != null ? user.getUserId() : null;
    }

    // 功能：标记已读——将 is_read 更新为 true
    @Transactional
    public Notification markRead(Long id) {
        Notification notification = baseMapper.selectById(id);
        if (notification == null) {
            throw new BusinessException(404, "通知不存在: " + id);
        }
        notification.setIsRead(true);
        baseMapper.updateById(notification);
        return notification;
    }
}

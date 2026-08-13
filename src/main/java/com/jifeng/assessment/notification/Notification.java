// 模块用途：通知消息实体——对应 notification 表，站内消息，无外键低耦合
// 依赖文件：无
// 修改注意：recipient_id 直接存 user_id，不加外键约束（Phase 2.0 内联通知，Phase 3 升级通用服务）
package com.jifeng.assessment.notification;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notification")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("recipient_id")
    private String recipientId;

    private String title;

    private String content;

    private String type;

    @TableField("is_read")
    private Boolean isRead;

    @TableField("target_url")
    private String targetUrl;

    private LocalDateTime createdAt;
}

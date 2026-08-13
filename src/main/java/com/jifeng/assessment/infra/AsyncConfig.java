// 模块用途：异步执行配置——开启 @Async 支持，用于通知批量发送等非关键异步任务
// 依赖文件：无
// 修改注意：通知发送失败不影响主流程，线程池队列满时丢弃并记录日志
package com.jifeng.assessment.infra;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class AsyncConfig {
}

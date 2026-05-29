// 模块用途：系统参数业务逻辑——查询全部参数、批量更新（乐观锁防并发覆盖）
// 依赖文件：SystemParamMapper.java, SystemParam.java
// 修改注意：param_key 不可修改（业务主键），批量更新任一失败则整体回滚
package com.jifeng.assessment.system;

import com.jifeng.assessment.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SystemParamService {

    private final SystemParamMapper systemParamMapper;

    // 功能：查询所有系统参数
    public List<SystemParam> listAll() {
        return systemParamMapper.selectList(null);
    }

    // 功能：批量更新系统参数——乐观锁防并发，任一冲突则整体回滚
    @Transactional
    public void batchUpdate(List<SystemParam> updates) {
        for (SystemParam update : updates) {
            SystemParam existing = systemParamMapper.selectById(update.getId());
            if (existing == null) {
                throw new BusinessException(404, "系统参数不存在: " + update.getId());
            }
            existing.setParamValue(update.getParamValue());
            if (update.getVersion() != null) {
                existing.setVersion(update.getVersion());
            }
            existing.setUpdatedAt(LocalDateTime.now());
            if (systemParamMapper.updateById(existing) == 0) {
                throw new BusinessException(409,
                        "参数" + existing.getParamKey() + "已被他人修改，请刷新后重试");
            }
        }
    }
}

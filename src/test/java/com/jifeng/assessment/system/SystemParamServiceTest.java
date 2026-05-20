// 模块用途：SystemParamService 单元测试——覆盖全量查询、批量更新、乐观锁冲突
// 依赖文件：SystemParamService.java, SystemParam.java, SystemParamMapper.java
// 修改注意：测试用 H2 内存库，每个用例独立，不要依赖执行顺序
package com.jifeng.assessment.system;

import com.jifeng.assessment.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SystemParamServiceTest {

    @Autowired
    private SystemParamService systemParamService;

    @Autowired
    private SystemParamMapper systemParamMapper;

    // 功能：查询全部系统参数，验证5条种子数据都存在
    @Test
    void shouldListAllSystemParams() {
        List<SystemParam> params = systemParamService.listAll();
        assertEquals(5, params.size());
        assertTrue(params.stream().anyMatch(p -> "NEED_PRESIDENT_CONFIRM".equals(p.getParamKey())));
        assertTrue(params.stream().anyMatch(p -> "MAX_RETURN_TIMES".equals(p.getParamKey())));
    }

    // 功能：批量更新——正确版本号更新成功，值生效
    @Test
    void shouldBatchUpdateSystemParams() {
        List<SystemParam> params = systemParamService.listAll();
        SystemParam first = params.get(0);
        Long originalVersion = first.getVersion();

        SystemParam update = new SystemParam();
        update.setId(first.getId());
        update.setParamValue("true");
        update.setVersion(originalVersion);
        systemParamService.batchUpdate(List.of(update));

        // 重新查询验证值已生效
        List<SystemParam> updated = systemParamService.listAll();
        SystemParam reloaded = updated.stream().filter(p -> p.getId().equals(first.getId())).findFirst().orElseThrow();
        assertEquals("true", reloaded.getParamValue());
    }

    // 功能：批量更新——传入不存在的ID返回404
    @Test
    void shouldRejectBatchUpdateWithNonexistentId() {
        SystemParam update = new SystemParam();
        update.setId(99999L);
        update.setParamValue("newValue");
        update.setVersion(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> systemParamService.batchUpdate(List.of(update)));
        assertEquals(404, ex.getCode());
        assertTrue(ex.getMessage().contains("不存在"));
    }

    // 功能：乐观锁并发冲突——用过期version调用batchUpdate返回409
    @Test
    void shouldRejectBatchUpdateWithStaleVersion() {
        List<SystemParam> params = systemParamService.listAll();
        SystemParam first = params.get(0);

        // 第一次正常更新推进version
        SystemParam update1 = new SystemParam();
        update1.setId(first.getId());
        update1.setParamValue("第一次修改");
        update1.setVersion(first.getVersion());
        systemParamService.batchUpdate(List.of(update1));

        // 用过期version再次更新应返回409
        SystemParam stale = new SystemParam();
        stale.setId(first.getId());
        stale.setParamValue("过期修改");
        stale.setVersion(first.getVersion());  // 过期version

        BusinessException ex = assertThrows(BusinessException.class,
                () -> systemParamService.batchUpdate(List.of(stale)));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已被他人修改"));
    }

    // 功能：批量更新——一次修改多个参数
    @Test
    void shouldBatchUpdateMultipleParams() {
        List<SystemParam> params = systemParamService.listAll();
        SystemParam p1 = params.get(0);
        SystemParam p2 = params.get(1);

        SystemParam u1 = new SystemParam();
        u1.setId(p1.getId());
        u1.setParamValue("true");
        u1.setVersion(p1.getVersion());

        SystemParam u2 = new SystemParam();
        u2.setId(p2.getId());
        u2.setParamValue("5");
        u2.setVersion(p2.getVersion());

        systemParamService.batchUpdate(List.of(u1, u2));

        List<SystemParam> updated = systemParamService.listAll();
        SystemParam r1 = updated.stream().filter(p -> p.getId().equals(p1.getId())).findFirst().orElseThrow();
        SystemParam r2 = updated.stream().filter(p -> p.getId().equals(p2.getId())).findFirst().orElseThrow();
        assertEquals("true", r1.getParamValue());
        assertEquals("5", r2.getParamValue());
    }

    // 功能：批量更新——任一参数冲突则整体回滚（@Transactional保证）
    @Test
    void shouldRollbackAllOnPartialConflict() {
        List<SystemParam> params = systemParamService.listAll();
        SystemParam first = params.get(0);

        // 先正常更新推进version
        SystemParam advance = new SystemParam();
        advance.setId(first.getId());
        advance.setParamValue("已修改");
        advance.setVersion(first.getVersion());
        systemParamService.batchUpdate(List.of(advance));

        // 批量更新：一个正确，一个过期version
        SystemParam good = new SystemParam();
        good.setId(params.get(1).getId());
        good.setParamValue("合法修改");
        good.setVersion(params.get(1).getVersion());

        SystemParam stale = new SystemParam();
        stale.setId(first.getId());
        stale.setParamValue("过期修改");
        stale.setVersion(first.getVersion());  // 过期version

        BusinessException ex = assertThrows(BusinessException.class,
                () -> systemParamService.batchUpdate(List.of(good, stale)));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已被他人修改"));
    }
}

// 模块用途：ProjectService 单元测试——覆盖创建项目、确认阶段、乐观锁冲突、重置阶段
// 依赖文件：ProjectService.java, ProjectMapper.java
// 修改注意：测试用 H2 内存库，每个用例独立，不要依赖执行顺序
package com.jifeng.assessment.project;

import com.jifeng.assessment.common.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProjectServiceTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectMapper projectMapper;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // 功能：创建项目成功，projectCode、projectStage正确返回，stageConfirmed默认为false
    @Test
    void shouldCreateProject() {
        Project project = new Project();
        project.setProjectCode("PJ001");
        project.setProjectName("测试项目一");
        project.setProjectStage("P3");

        ProjectDTO dto = projectService.createProject(project);
        assertNotNull(dto);
        assertEquals("PJ001", dto.getProjectCode());
        assertEquals("P3", dto.getProjectStage());
        assertFalse(dto.getStageConfirmed());
        assertEquals("ACTIVE", dto.getStatus());
    }

    // 功能：同一projectCode+projectStage重复时抛出409（联合主键），不同stage可创建
    @Test
    void shouldRejectDuplicateCodeStageCombo() {
        Project p1 = new Project();
        p1.setProjectCode("PJ_DUP");
        p1.setProjectName("重复项目");
        p1.setProjectStage("P2");
        projectService.createProject(p1);

        // 同编码不同阶段 — 应该成功
        Project p2 = new Project();
        p2.setProjectCode("PJ_DUP");
        p2.setProjectName("重复项目P3");
        p2.setProjectStage("P3");
        ProjectDTO dto2 = projectService.createProject(p2);
        assertNotNull(dto2);

        // 同编码同阶段 — 应该拒绝
        Project p3 = new Project();
        p3.setProjectCode("PJ_DUP");
        p3.setProjectName("重复项目二");
        p3.setProjectStage("P2");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.createProject(p3));
        assertTrue(ex.getMessage().contains("已存在"));
    }

    // 功能：无效projectStage时抛出400异常
    @Test
    void shouldRejectInvalidStage() {
        Project project = new Project();
        project.setProjectCode("PJ_BAD");
        project.setProjectName("无效阶段项目");
        project.setProjectStage("P99");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.createProject(project));
        assertTrue(ex.getMessage().contains("无效的项目阶段"));
    }

    // 功能：PM确认阶段成功，confirmedBy记录当前用户，confirmedAt非空
    @Test
    void shouldConfirmStage() {
        Project project = new Project();
        project.setProjectCode("PJ_CONFIRM");
        project.setProjectName("阶段确认项目");
        project.setProjectStage("P4");
        projectService.createProject(project);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("pm_zhang", null,
                        List.of(new SimpleGrantedAuthority("ROLE_PM"))));

        ProjectDTO dto = projectService.confirmStage("PJ_CONFIRM", "P4");
        assertTrue(dto.getStageConfirmed());
        assertEquals("pm_zhang", dto.getConfirmedBy());
        assertNotNull(dto.getConfirmedAt());
    }

    // 功能：已确认阶段再次确认时抛出400异常
    @Test
    void shouldRejectDuplicateConfirm() {
        Project project = new Project();
        project.setProjectCode("PJ_CONF2");
        project.setProjectName("重复确认项目");
        project.setProjectStage("P3");
        projectService.createProject(project);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("pm_li", null,
                        List.of(new SimpleGrantedAuthority("ROLE_PM"))));

        projectService.confirmStage("PJ_CONF2", "P3");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.confirmStage("PJ_CONF2", "P3"));
        assertTrue(ex.getMessage().contains("已确认"));
    }

    // 功能：并发确认时乐观锁冲突返回409——模拟version不匹配场景
    @Test
    void shouldThrowOptimisticLockOnConflict() {
        Project project = new Project();
        project.setProjectCode("PJ_CONCUR");
        project.setProjectName("并发测试项目");
        project.setProjectStage("P5");
        projectService.createProject(project);

        // 模拟并发：通过selectByCodeAndStage读取并更新，推进version
        Project fresh = projectMapper.selectByCodeAndStage("PJ_CONCUR", "P5");
        fresh.setDescription("并发修改的内容");
        projectMapper.updateById(fresh); // version now 1

        // 构造一个version=0的过期实体，模拟并发窗口期
        Project stale = new Project();
        stale.setProjectCode("PJ_CONCUR");
        stale.setProjectStage("P5");
        stale.setVersion(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.updateWithOptimisticLock(stale));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已被他人修改"));
    }

    // 功能：ADMIN重置阶段后stageConfirmed变false，confirmedBy和confirmedAt被清空
    @Test
    void shouldResetStage() {
        Project project = new Project();
        project.setProjectCode("PJ_RESET");
        project.setProjectName("重置阶段项目");
        project.setProjectStage("P2");
        projectService.createProject(project);

        // 先确认
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("pm_wang", null,
                        List.of(new SimpleGrantedAuthority("ROLE_PM"))));
        projectService.confirmStage("PJ_RESET", "P2");
        SecurityContextHolder.clearContext();

        // 重置
        ProjectDTO dto = projectService.resetStage("PJ_RESET", "P2");
        assertFalse(dto.getStageConfirmed());
        assertNull(dto.getConfirmedBy());
        assertNull(dto.getConfirmedAt());
    }
}

-- V8: project 表改为联合主键 (project_code, project_stage)
-- 目标：同一 projectCode 可创建多个阶段（P1/P2/P3/P4/P5），但同编码+同阶段不可重复

-- 1. 删除引用了 project_code 的外键
ALTER TABLE IF EXISTS project_role_assignment DROP CONSTRAINT IF EXISTS fk_pra_project;

-- 2. 删除 project_code 为主键的约束
ALTER TABLE project DROP CONSTRAINT IF EXISTS project_pkey;

-- 3. 设置联合主键 (project_code, project_stage)
ALTER TABLE project ADD PRIMARY KEY (project_code, project_stage);

-- 4. 为 project_role_assignment 增加 project_stage 列（已有行默认为 NULL）
ALTER TABLE project_role_assignment ADD COLUMN IF NOT EXISTS project_stage VARCHAR(8);

-- 5. 重建外键（引用联合主键）
ALTER TABLE project_role_assignment
    ADD CONSTRAINT fk_pra_project
    FOREIGN KEY (project_code, project_stage)
    REFERENCES project(project_code, project_stage);

COMMENT ON COLUMN project_role_assignment.project_stage IS '项目阶段——配合 project 联合主键';

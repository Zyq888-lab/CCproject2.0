CREATE TABLE position_category (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    deleted     SMALLINT     NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_position_category_name UNIQUE (name, deleted)
);

COMMENT ON TABLE position_category IS '岗位分类表';
COMMENT ON COLUMN position_category.sort_order IS '排序，数字越小越靠前';
COMMENT ON COLUMN position_category.deleted IS '逻辑删除 0=未删除 1=已删除';

INSERT INTO position_category (name, sort_order)
SELECT DISTINCT category, ROW_NUMBER() OVER (ORDER BY category) * 10
FROM position_assessment_config
WHERE category IS NOT NULL AND category != '';

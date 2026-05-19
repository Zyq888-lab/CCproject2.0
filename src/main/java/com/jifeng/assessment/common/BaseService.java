package com.jifeng.assessment.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public abstract class BaseService<M extends BaseMapper<T>, T> extends ServiceImpl<M, T> {

    /**
     * 分页查询
     */
    public PageResult<T> selectPage(PageQuery query) {
        return selectPage(query, null);
    }

    /**
     * 分页查询（带条件）
     */
    public PageResult<T> selectPage(PageQuery query, LambdaQueryWrapper<T> wrapper) {
        Page<T> page = Page.of(query.getPage(), query.getSize());
        Page<T> result = wrapper == null ? baseMapper.selectPage(page, null)
                : baseMapper.selectPage(page, wrapper);
        return PageResult.of(result.getTotal(), query.getPage(), query.getSize(), result.getRecords());
    }

    /**
     * 带乐观锁的更新 — version字段由MyBatis-Plus自动处理
     * 若更新影响行数为0，表示版本冲突
     */
    @Transactional
    public boolean updateWithOptimisticLock(T entity) {
        int rows = baseMapper.updateById(entity);
        if (rows == 0) {
            throw new BusinessException(409, "数据已被他人修改，请刷新后重试");
        }
        return true;
    }

    public List<T> listAll() {
        return baseMapper.selectList(null);
    }
}

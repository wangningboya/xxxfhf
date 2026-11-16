package com.xxxfhf.meal.common;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/**
 * 分页响应体，字段名与技术文档 3.1 的约定一致：{ list, total, page, pageSize }。
 *
 * 不直接返回 MyBatis-Plus 的 IPage：它的字段是 records / current / size，
 * 跟文档约定不一致，前端会拿到一堆用不上的字段（pages、orders 等）。
 */
public record PageResult<T>(List<T> list, long total, long page, long pageSize) {

    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }
}

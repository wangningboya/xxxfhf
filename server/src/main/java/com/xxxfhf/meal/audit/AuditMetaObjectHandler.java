package com.xxxfhf.meal.audit;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计四列的自动填充。
 *
 * <p>注册成 Bean 即生效，无需在实体上额外配置——只要字段标了
 * {@code @TableField(fill = ...)}。
 *
 * <p><b>覆盖不到的写法</b>：{@code update(null, Wrappers.lambdaUpdate()...)}。
 * 那种调用没有实体对象可填，updated_by 会静默为空，需要用
 * {@link AuditHelper#stamp} 手工补。这是本项目最容易漏的一处，
 * 新增更新语句时先想一下属于哪一种。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        Long actor = AuditHelper.actor();
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "createdBy", Long.class, actor);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedBy", Long.class, actor);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // strictUpdateFill 只对 fill 含 UPDATE 的字段生效，不会误填 created_*
        strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
        strictUpdateFill(metaObject, "updatedBy", Long.class, AuditHelper.actor());
    }
}

package com.xxxfhf.meal.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 装配。
 *
 * 分页插件依赖 JSqlParser 做 COUNT 语句改写；自 MyBatis-Plus 3.5.9 起该类库已从主包拆出，
 * 由 pom.xml 中的 mybatis-plus-jsqlparser 提供（版本与主包一致）。
 * 少了它不会启动失败，而是第一次调到分页接口时才抛 NoClassDefFoundError，排查成本更高。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页上限，防止前端传 pageSize=999999 打满内存
        pagination.setMaxLimit(200L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}

package com.xxxfhf.meal;

import com.xxxfhf.meal.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 群内点餐服务端入口。
 *
 * 说明：
 * 1. 不引 spring-boot-starter-actuator，健康检查由 health 包自写，避免把管理端点暴露在生产环境。
 * 2. 定时任务为单实例模式（不引 Redis、不做分布式调度）。
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableScheduling
public class MealApplication {

    public static void main(String[] args) {
        SpringApplication.run(MealApplication.class, args);
    }
}

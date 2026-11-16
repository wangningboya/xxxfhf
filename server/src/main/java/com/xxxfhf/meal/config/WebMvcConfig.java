package com.xxxfhf.meal.config;

import com.xxxfhf.meal.auth.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Web 层装配：登录态拦截、上传目录静态映射、跨域来源白名单。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebMvcConfig.class);

    /** 免登录路径：两个登录接口、健康检查、错误转发、图片静态资源 */
    private static final String[] WHITE_LIST = {
            "/auth/login", "/auth/admin/login", "/health", "/ready", "/error", "/upload/**"
    };

    private final AuthInterceptor authInterceptor;
    private final AppProperties appProperties;

    public WebMvcConfig(AuthInterceptor authInterceptor, AppProperties appProperties) {
        this.authInterceptor = authInterceptor;
        this.appProperties = appProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(WHITE_LIST);
    }

    /**
     * 本地开发时让 /upload/** 也能直接访问（生产由 Nginx 直出，不经应用）。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = Paths.get(appProperties.getUpload().getDir()).toAbsolutePath().normalize();
        String location = dir.toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        String pattern = appProperties.getUpload().getUrlPrefix();
        if (!pattern.endsWith("/**")) {
            pattern = pattern + "/**";
        }
        registry.addResourceHandler(pattern).addResourceLocations(location);
        log.info("图片静态映射 {} -> {}", pattern, location);
    }

    /**
     * 跨域只对网页后台开发地址开放，且来源必须显式列出（禁止通配符）。
     * 生产环境前后端同域反代（/api/** 到应用），不依赖跨域。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var origins = appProperties.getWeb().getAllowedOrigins();
        if (origins == null || origins.isEmpty()) {
            return;
        }
        registry.addMapping("/**")
                .allowedOrigins(origins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization", "X-Request-Id")
                .exposedHeaders("X-Request-Id")
                .allowCredentials(false)
                .maxAge(3600);
    }
}

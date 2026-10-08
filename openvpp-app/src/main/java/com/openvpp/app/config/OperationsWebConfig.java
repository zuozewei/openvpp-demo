package com.openvpp.app.config;

import com.openvpp.app.auth.AuthTokenInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 运营接口 MVC 装配：登录态拦截器只挂在 /api/v1/operations/** 命名空间，
 * 登录入口 /operations/auth/login 显式排除（登录前无会话，由 AuthService 自行校验账号口令）；
 * 既有 /api/v1/demo 贯穿案例入口不挂拦截器，教学演示不受登录态影响。
 */
@Configuration
public class OperationsWebConfig implements WebMvcConfigurer {

    private final AuthTokenInterceptor authTokenInterceptor;
    private final String apiPrefix;

    public OperationsWebConfig(AuthTokenInterceptor authTokenInterceptor,
                               @Value("${openvpp.api-prefix:/api/v1}") String apiPrefix) {
        this.authTokenInterceptor = authTokenInterceptor;
        this.apiPrefix = apiPrefix;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authTokenInterceptor)
                .addPathPatterns(apiPrefix + "/operations/**")
                .excludePathPatterns(apiPrefix + "/operations/auth/login");
    }
}

package cn.campus.studyroom;

import jakarta.servlet.http.*;
import java.time.*;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

/** HTTP鉴权、按环境跨域与业务时钟配置；仅依赖Security Crypto，认证由MVC拦截器与业务服务完成。 */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {
  private final BookingService service;
  private final String[] allowedOrigins;

  /** 注入会话服务与逗号分隔来源；无Spring Security FilterChain，不把密码库误称为完整安全认证链。 */
  public WebConfiguration(
      BookingService service,
      @Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
          String origins) {
    this.service = service;
    this.allowedOrigins =
        Arrays.stream(origins.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isEmpty())
            .distinct()
            .toArray(String[]::new);
  }

  /** 上海业务Clock；测试可用@Primary固定Clock进行边界复现。 */
  @Bean
  public static Clock businessClock() {
    return Clock.system(ZoneId.of("Asia/Shanghai"));
  }

  /** 拦截所有私有API；OPTIONS交由跨域处理，登录注册与健康公开。 */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              /** 会话认证成功后仅传递userId，管理员权限在业务服务再次校验。 */
              @Override
              public boolean preHandle(
                  HttpServletRequest request, HttpServletResponse response, Object handler) {
                if ("OPTIONS".equals(request.getMethod())) return true;
                request.setAttribute(
                    "actorId", service.authenticate(request.getHeader("Authorization")));
                return true;
              }
            })
        .addPathPatterns("/api/**")
        .excludePathPatterns("/api/auth/login", "/api/auth/register", "/api/health");
  }

  /** 使用环境配置的明确来源；开发默认5173，prod仅指定HTTPS域名，Bearer无需浏览器cookie。 */
  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOrigins(allowedOrigins)
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("Authorization", "Content-Type");
  }
}

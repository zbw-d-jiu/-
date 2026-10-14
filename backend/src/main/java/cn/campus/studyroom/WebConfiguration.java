package cn.campus.studyroom;

import jakarta.servlet.http.*;
import java.time.*;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

/** HTTP鉴权、开发跨域与业务时钟配置；静态资源和健康检查不需要令牌。 */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {
  private final BookingService service;

  /** 注入服务，所有鉴权依赖持久化会话而非前端role。 */
  public WebConfiguration(BookingService service) {
    this.service = service;
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

  /** 本机开发页面可访问API；不允许任意来源，Bearer无需浏览器cookie。 */
  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("Authorization", "Content-Type");
  }
}

package cn.campus.studyroom;

import static cn.campus.studyroom.ApiTypes.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.*;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/** 固定前后端API契约；输入校验失败400，认证401，权限403，冲突409。 */
@RestController
@RequestMapping("/api")
public class ApiController {
  private final BookingService service;

  /** 注入事务服务，控制器不直接写数据库。 */
  public ApiController(BookingService service) {
    this.service = service;
  }

  private long actor(HttpServletRequest request) {
    return (Long) request.getAttribute("actorId");
  }

  /** 公开健康状态，能响应代表应用已完成数据库启动。 */
  @GetMapping("/health")
  public Result health() {
    return Result.ok(Map.of("status", "UP", "timezone", "Asia/Shanghai"));
  }

  /** 公开注册，服务端固定STUDENT角色。 */
  @PostMapping("/auth/register")
  public Result register(@Valid @RequestBody Register dto) {
    return Result.ok(service.register(dto));
  }

  /** 公开登录，返回随机令牌与公开资料。 */
  @PostMapping("/auth/login")
  public Result login(@Valid @RequestBody Login dto) {
    return Result.ok(service.login(dto));
  }

  /** 已登录者查询本人资料。 */
  @GetMapping("/auth/me")
  public Result me(HttpServletRequest r) {
    return Result.ok(service.me(actor(r)));
  }

  /** 已登录者白名单更新本人资料。 */
  @PutMapping("/auth/me")
  public Result profile(HttpServletRequest r, @Valid @RequestBody Profile dto) {
    return Result.ok(service.profile(actor(r), dto));
  }

  /** 注销当前Bearer令牌，立即失效。 */
  @PostMapping("/auth/logout")
  public Result logout(HttpServletRequest r) {
    return Result.ok(service.logout(r.getHeader("Authorization")));
  }

  /** 已登录者查询房间，完整时段可计算空闲状态。 */
  @GetMapping("/rooms")
  public Result rooms(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") LocalTime startTime,
      @RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") LocalTime endTime) {
    return Result.ok(service.rooms(date, startTime, endTime));
  }

  /** 房间详情，已软删除资源404。 */
  @GetMapping("/rooms/{id}")
  public Result room(@PathVariable long id) {
    return Result.ok(service.getRoom(id));
  }

  /** 管理员创建资源。 */
  @PostMapping("/admin/rooms")
  public Result addRoom(HttpServletRequest r, @Valid @RequestBody RoomInput dto) {
    return Result.ok(service.saveRoom(actor(r), null, dto));
  }

  /** 管理员修改资源，关闭前校验有效预约。 */
  @PutMapping("/admin/rooms/{id}")
  public Result updateRoom(
      HttpServletRequest r, @PathVariable long id, @Valid @RequestBody RoomInput dto) {
    return Result.ok(service.saveRoom(actor(r), id, dto));
  }

  /** 管理员软删除资源，历史记录保留。 */
  @DeleteMapping("/admin/rooms/{id}")
  public Result deleteRoom(HttpServletRequest r, @PathVariable long id) {
    return Result.ok(service.deleteRoom(actor(r), id));
  }

  /** 学生预约，双维并发冲突409、处罚封禁403。 */
  @PostMapping("/orders")
  public Result createOrder(HttpServletRequest r, @Valid @RequestBody BookingInput dto) {
    return Result.ok(service.createOrder(actor(r), dto));
  }

  /** 学生本人完整历史预约。 */
  @GetMapping("/orders/me")
  public Result orders(HttpServletRequest r) {
    return Result.ok(service.orders(actor(r), false));
  }

  /** 本人开始前取消，重复本人取消幂等。 */
  @PostMapping("/orders/{id}/cancel")
  public Result cancel(HttpServletRequest r, @PathVariable long id) {
    return Result.ok(service.cancel(actor(r), id, false, "学生本人开始前取消"));
  }

  /** 本人全部违规及撤销说明。 */
  @GetMapping("/violations/me")
  public Result violations(HttpServletRequest r) {
    return Result.ok(service.violations(actor(r), false));
  }

  /** 管理员全量预约记录。 */
  @GetMapping("/admin/orders")
  public Result adminOrders(HttpServletRequest r) {
    return Result.ok(service.orders(actor(r), true));
  }

  /** 管理员确认可靠到场证据和完成，提供原因。 */
  @PostMapping("/admin/orders/{id}/attendance")
  public Result attendance(
      HttpServletRequest r, @PathVariable long id, @Valid @RequestBody AttendanceInput dto) {
    return Result.ok(service.attendance(actor(r), id, dto));
  }

  /** 管理员理由取消异常预约，不允许取消完成或爽约记录。 */
  @PostMapping("/admin/orders/{id}/cancel")
  public Result adminCancel(
      HttpServletRequest r, @PathVariable long id, @Valid @RequestBody Reason dto) {
    return Result.ok(service.cancel(actor(r), id, true, dto.reason()));
  }

  /** 管理员学生名单，不含密码哈希。 */
  @GetMapping("/admin/users")
  public Result users(HttpServletRequest r) {
    return Result.ok(service.users(actor(r)));
  }

  /** 管理员全部违规证据。 */
  @GetMapping("/admin/violations")
  public Result adminViolations(HttpServletRequest r) {
    return Result.ok(service.violations(actor(r), true));
  }

  /** 管理员保留证据撤销并自动重算处罚，不提供任意解禁接口。 */
  @PostMapping("/admin/violations/{id}/revoke")
  public Result revoke(
      HttpServletRequest r, @PathVariable long id, @Valid @RequestBody Reason dto) {
    return Result.ok(service.revoke(actor(r), id, dto.reason()));
  }

  /** 管理员日期统计，默认今天，管理员证据不冒充传感器数据。 */
  @GetMapping("/admin/statistics")
  public Result statistics(
      HttpServletRequest r,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return Result.ok(service.statistics(actor(r), date));
  }
}

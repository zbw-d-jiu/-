package cn.campus.studyroom;

import jakarta.validation.constraints.*;
import java.time.*;

/** API白名单输入与统一响应。未知字段由Jackson拒绝，防止role/isBan等敏感字段注入。 */
public final class ApiTypes {
  private ApiTypes() {}

  /** 统一响应；失败时data为空，HTTP状态保留错误类别。 */
  public record Result(boolean success, Object data, String message) {
    /** 包装成功结果，不回传实体及其敏感字段。 */
    public static Result ok(Object data) {
      return new Result(true, data, "操作成功");
    }
  }

  /** 注册学生输入；密码至少8字符、最多72字符，服务层另验证BCrypt的UTF-8最多72字节限制。 */
  public record Register(
      @NotBlank @Size(max = 30) String stuNo,
      @NotBlank @Size(max = 40) String userName,
      @NotBlank @Size(min = 8, max = 72) String password,
      @Size(max = 20) String phone) {}

  /** 学号密码登录输入。 */
  public record Login(@NotBlank String stuNo, @NotBlank String password) {}

  /** 仅允许修改姓名及联系方式。 */
  public record Profile(@NotBlank @Size(max = 40) String userName, @Size(max = 20) String phone) {}

  /** 房间写入；本版每间固定30个名额，拒绝任意更改容量，状态只允许开放或关闭。 */
  public record RoomInput(
      @NotBlank @Size(max = 30) String roomNo,
      @NotBlank @Size(max = 30) String floor,
      @Min(30) @Max(30) int capacity,
      @Pattern(regexp = "OPEN|CLOSED") @NotNull String status,
      @Size(max = 200) String remark) {}

  /** 半开预约区间，字符串由Jackson按ISO日期及HH:mm解析。 */
  public record BookingInput(
      @NotNull Long roomId,
      @NotNull LocalDate orderDate,
      @NotNull LocalTime startTime,
      @NotNull LocalTime endTime) {}

  /** 到场动作须提供可解释的管理员原因。 */
  public record AttendanceInput(
      @NotNull @Pattern(regexp = "ARRIVED|LATE|NO_SHOW|COMPLETE") String action,
      @NotBlank @Size(max = 500) String reason) {}

  /** 异常取消和违规撤销均需审计理由。 */
  public record Reason(@NotBlank @Size(max = 500) String reason) {}

  /** 业务异常显式携带HTTP分类，事务自动回滚。 */
  public static class BusinessError extends RuntimeException {
    public final int status;

    /** 创建业务失败；输入status必须是HTTP错误类别，message为用户可读中文。 */
    public BusinessError(int status, String message) {
      super(message);
      this.status = status;
    }
  }
}

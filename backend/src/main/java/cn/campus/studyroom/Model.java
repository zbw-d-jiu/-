package cn.campus.studyroom;

import jakarta.persistence.*;
import java.time.*;

/** 持久化模型。实体只在服务内使用，API显式映射白名单，避免泄露密码与令牌哈希。 */
public final class Model {
  private Model() {}

  /** 用户及当前处罚快照；违规事务始终锁定此行，避免计数丢失。 */
  @Entity(name = "CampusUser")
  @Table(name = "campus_user")
  public static class User {
    /** 自增账号主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 唯一学号；管理员合成账号也在此登录。 */
    @Column(name = "stu_no", nullable = false, unique = true, length = 30)
    public String stuNo;

    /** 姓名。 */
    @Column(name = "user_name", nullable = false, length = 40)
    public String userName;

    /** BCrypt密码哈希，禁止返回API。 */
    @Column(name = "password_hash", nullable = false, length = 100)
    public String passwordHash;

    /** 服务端分配STUDENT或ADMIN，注册不能指定。 */
    @Column(nullable = false, length = 10)
    public String role;

    /** 当前未撤销违规数量。 */
    @Column(name = "violate_count", nullable = false)
    public int violateCount;

    /** 根据违规数量>=3自动派生。 */
    @Column(name = "is_ban", nullable = false)
    public boolean isBan;

    /** 联系电话。 */
    @Column(length = 20)
    public String phone;
  }

  /** 多人共享房间资源；每间固定30个名额，软删除保留历史订单关联。 */
  @Entity(name = "StudyRoom")
  @Table(name = "study_room")
  public static class Room {
    /** 房间主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 唯一房间编号，软删除后不重用。 */
    @Column(name = "room_no", nullable = false, unique = true, length = 30)
    public String roomNo;

    /** 楼层说明。 */
    @Column(nullable = false, length = 30)
    public String floor;

    /** 固定30人容量，每个学生预约占1名额，峰值占用不得超过此容量。 */
    @Column(nullable = false)
    public int capacity;

    /** OPEN或CLOSED。 */
    @Column(nullable = false, length = 10)
    public String status;

    /** 管理员备注。 */
    @Column(length = 200)
    public String remark;

    /** 历史保留标志，列表隐藏已删除房间。 */
    @Column(nullable = false)
    public boolean deleted;
  }

  /** 预约订单；仅取消释放时段，完成和爽约仍占原区间。 */
  @Entity(name = "Booking")
  @Table(name = "booking")
  public static class Order {
    /** 订单主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 预约学生外键。 */
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    public User user;

    /** 共享房间外键；本订单占用一个名额而非整间独占。 */
    @ManyToOne(optional = false)
    @JoinColumn(name = "room_id")
    public Room room;

    /** 上海预约日期。 */
    @Column(name = "order_date", nullable = false)
    public LocalDate orderDate;

    /** 半开区间起点。 */
    @Column(name = "start_time", nullable = false)
    public LocalTime startTime;

    /** 半开区间终点。 */
    @Column(name = "end_time", nullable = false)
    public LocalTime endTime;

    /** RESERVED/COMPLETED/CANCELLED/NO_SHOW。 */
    @Column(nullable = false, length = 12)
    public String status;

    /** UNKNOWN/ARRIVED/LATE/NO_SHOW管理员证据。 */
    @Column(nullable = false, length = 10)
    public String attendance;

    /** 到场确认真实时间，未知时为空。 */
    @Column(name = "arrived_at")
    public LocalDateTime arrivedAt;

    /** 管理员完成确认真实时间。 */
    @Column(name = "completed_at")
    public LocalDateTime completedAt;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;
  }

  /** 每单最多一条活动违规证据；撤销历史保留，可依新证据重新分类。 */
  @Entity(name = "ViolationEvent")
  @Table(name = "violation_event")
  public static class Violation {
    /** 违规主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 所属学生外键。 */
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    public User user;

    /** 所属订单外键，允许保留多条已撤销历史。 */
    @ManyToOne(optional = false)
    @JoinColumn(name = "order_id")
    public Order order;

    /** 活动时等于订单ID，撤销后置空；唯一索引允许多个null，实现至多一个活动事件。 */
    @Column(name = "active_order_id", unique = true)
    public Long activeOrderId;

    /** LATE或NO_SHOW。 */
    @Column(nullable = false, length = 10)
    public String type;

    /** 管理员证据原因。 */
    @Column(nullable = false, length = 500)
    public String reason;

    /** 首次证据发生时间。 */
    @Column(name = "occurred_at", nullable = false)
    public LocalDateTime occurredAt;

    /** 是否已纠错撤销。 */
    @Column(nullable = false)
    public boolean revoked;

    /** 撤销真实时间。 */
    @Column(name = "revoked_at")
    public LocalDateTime revokedAt;

    /** 撤销依据。 */
    @Column(name = "revoke_reason", length = 500)
    public String revokeReason;
  }

  /** 登录会话只保存SHA256令牌摘要；注销立即撤销。 */
  @Entity(name = "LoginSession")
  @Table(name = "login_session")
  public static class Session {
    /** 摘要主键，不储存可直接使用的token。 */
    @Id
    @Column(name = "token_hash", length = 64)
    public String tokenHash;

    /** 会话用户。 */
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    public User user;

    /** 16小时到期时间。 */
    @Column(name = "expires_at", nullable = false)
    public LocalDateTime expiresAt;
  }

  /** 管理及取消审计；追加写入，保留操作者、原因和状态影响。 */
  @Entity(name = "OperationAudit")
  @Table(name = "operation_audit")
  public static class Audit {
    /** 审计主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 操作者用户主键，历史记录不随账号删除。 */
    @Column(name = "actor_id", nullable = false)
    public Long actorId;

    /** 操作动作。 */
    @Column(nullable = false, length = 40)
    public String action;

    /** 关联资源类型和主键。 */
    @Column(name = "target_ref", nullable = false, length = 80)
    public String targetRef;

    /** 原因及状态变化说明。 */
    @Column(nullable = false, length = 1000)
    public String reason;

    /** 真实操作时间。 */
    @Column(name = "occurred_at", nullable = false)
    public LocalDateTime occurredAt;
  }
}

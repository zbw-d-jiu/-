package cn.campus.studyroom;

import static cn.campus.studyroom.ApiTypes.*;
import static cn.campus.studyroom.Model.*;

import jakarta.persistence.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 六模块业务服务。全部写入以数据库事务执行；双维并发预约使用用户→房间固定锁序。 */
@Service
// READ_COMMITTED避免管理员鉴权的早期快照使后续加锁后仍读取陈旧违规计数；锁仍是并发约束主体。
@Transactional(isolation = Isolation.READ_COMMITTED)
public class BookingService implements ApplicationRunner {
  @PersistenceContext private EntityManager em;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
  private final SecureRandom random = new SecureRandom();
  @Autowired private Clock clock;

  @Value("${app.booking-days:7}")
  private int bookingDays;

  @Value("${app.demo-seed:false}")
  private boolean demoSeed;

  /** 返回上海当前时间；不依赖宿主机默认时区。 */
  public LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  private void require(boolean condition, int code, String message) {
    if (!condition) throw new BusinessError(code, message);
  }

  private User user(long id, boolean lock) {
    User u =
        lock ? em.find(User.class, id, LockModeType.PESSIMISTIC_WRITE) : em.find(User.class, id);
    require(u != null, 404, "用户不存在");
    if (lock) em.refresh(u);
    return u;
  }

  private Room room(long id, boolean lock) {
    Room r =
        lock ? em.find(Room.class, id, LockModeType.PESSIMISTIC_WRITE) : em.find(Room.class, id);
    require(r != null, 404, "房间不存在");
    if (lock) em.refresh(r);
    return r;
  }

  private Order order(long id) {
    Order o = em.find(Order.class, id);
    require(o != null, 404, "预约不存在");
    return o;
  }

  private void admin(User actor) {
    require("ADMIN".equals(actor.role), 403, "仅管理员可操作");
  }

  private void student(User actor) {
    require("STUDENT".equals(actor.role), 403, "仅学生可预约");
  }

  private String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private Map<String, Object> map(Object... pairs) {
    Map<String, Object> value = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
    return value;
  }

  private Map<String, Object> userDto(User u) {
    return map(
        "id",
        u.id,
        "stuNo",
        u.stuNo,
        "userName",
        u.userName,
        "phone",
        u.phone,
        "role",
        u.role,
        "violateCount",
        u.violateCount,
        "isBan",
        u.isBan);
  }

  private Map<String, Object> roomDto(Room r, boolean available) {
    return map(
        "id",
        r.id,
        "roomNo",
        r.roomNo,
        "floor",
        r.floor,
        "capacity",
        r.capacity,
        "status",
        r.status,
        "remark",
        r.remark,
        "available",
        available);
  }

  private Map<String, Object> orderDto(Order o) {
    return map(
        "id",
        o.id,
        "userId",
        o.user.id,
        "userName",
        o.user.userName,
        "stuNo",
        o.user.stuNo,
        "roomId",
        o.room.id,
        "roomNo",
        o.room.roomNo,
        "orderDate",
        o.orderDate,
        "startTime",
        o.startTime.toString(),
        "endTime",
        o.endTime.toString(),
        "status",
        o.status,
        "attendance",
        o.attendance,
        "arrivedAt",
        o.arrivedAt,
        "completedAt",
        o.completedAt,
        "createdAt",
        o.createdAt);
  }

  private Map<String, Object> violationDto(Violation v) {
    return map(
        "id",
        v.id,
        "userId",
        v.user.id,
        "userName",
        v.user.userName,
        "stuNo",
        v.user.stuNo,
        "orderId",
        v.order.id,
        "type",
        v.type,
        "reason",
        v.reason,
        "occurredAt",
        v.occurredAt,
        "revoked",
        v.revoked,
        "revokedAt",
        v.revokedAt,
        "revokeReason",
        v.revokeReason);
  }

  private void audit(User actor, String action, String target, String reason) {
    Audit a = new Audit();
    a.actorId = actor.id;
    a.action = action;
    a.targetRef = target;
    a.reason = reason;
    a.occurredAt = now();
    em.persist(a);
  }

  private Map<String, Object> session(User u) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Session s = new Session();
    s.tokenHash = hash(token);
    s.user = u;
    s.expiresAt = now().plusHours(16);
    em.persist(s);
    return map("token", token, "user", userDto(u));
  }

  /** 检验Bearer会话，过期或无效统一401；只向控制器提供当前用户ID。 */
  @Transactional(readOnly = true)
  public Long authenticate(String bearer) {
    require(bearer != null && bearer.startsWith("Bearer "), 401, "请先登录");
    Session s = em.find(Session.class, hash(bearer.substring(7)));
    require(s != null && s.expiresAt.isAfter(now()), 401, "登录已失效，请重新登录");
    return s.user.id;
  }

  /** 强制注册STUDENT；唯一学号冲突返回409，哈希不进入响应。 */
  public Object register(Register dto) {
    validatePasswordBytes(dto.password());
    require(
        em.createQuery("select count(u) from CampusUser u where u.stuNo=:no", Long.class)
                .setParameter("no", dto.stuNo())
                .getSingleResult()
            == 0,
        409,
        "学号已注册");
    User u = new User();
    u.stuNo = dto.stuNo();
    u.userName = dto.userName();
    u.passwordHash = encoder.encode(dto.password());
    u.phone = dto.phone();
    u.role = "STUDENT";
    em.persist(u);
    em.flush();
    return session(u);
  }

  /** 登录凭据错误不区分账号存在性，避免泄露账号信息。 */
  public Object login(Login dto) {
    validatePasswordBytes(dto.password());
    List<User> found =
        em.createQuery("select u from CampusUser u where u.stuNo=:no", User.class)
            .setParameter("no", dto.stuNo())
            .getResultList();
    require(
        !found.isEmpty() && encoder.matches(dto.password(), found.getFirst().passwordHash),
        401,
        "学号或密码错误");
    return session(found.getFirst());
  }

  /** 当前用户资料，只包含公开白名单字段。 */
  @Transactional(readOnly = true)
  public Object me(long actor) {
    return userDto(user(actor, false));
  }

  /** 修改本人姓名电话，不改变角色、违规或密码。 */
  public Object profile(long actor, Profile dto) {
    User u = user(actor, true);
    u.userName = dto.userName();
    u.phone = dto.phone();
    return userDto(u);
  }

  /** 删除当前令牌会话；其他设备会话不受影响。 */
  public Object logout(String bearer) {
    Session s = em.find(Session.class, hash(bearer.substring(7)));
    if (s != null) em.remove(s);
    return null;
  }

  private void validateWindow(LocalDate date, LocalTime start, LocalTime end, boolean future) {
    require(
        start.getSecond() == 0
            && start.getNano() == 0
            && end.getSecond() == 0
            && end.getNano() == 0,
        400,
        "时段精度必须为分钟");
    require(
        start.isBefore(end)
            && !start.isBefore(LocalTime.of(8, 0))
            && !end.isAfter(LocalTime.of(22, 0)),
        400,
        "预约时段必须在08:00-22:00且开始早于结束");
    if (future) {
      LocalDateTime current = now();
      require(
          !date.isBefore(current.toLocalDate())
              && !date.isAfter(current.toLocalDate().plusDays(bookingDays)),
          400,
          "预约日期必须在今天至未来" + bookingDays + "天内");
      require(date.atTime(start).isAfter(current), 400, "预约开始时间必须严格晚于当前时间");
    }
  }

  private long conflicts(Long userId, Long roomId, LocalDate date, LocalTime start, LocalTime end) {
    // 半开区间：已有start<新end且已有end>新start；相邻边界不冲突。仅取消释放占用。
    String field = userId != null ? "o.user.id" : "o.room.id";
    return em.createQuery(
            "select count(o) from Booking o where "
                + field
                + "=:id and o.orderDate=:day and o.status <> 'CANCELLED' and o.startTime < :end and"
                + " o.endTime > :start",
            Long.class)
        .setParameter("id", userId != null ? userId : roomId)
        .setParameter("day", date)
        .setParameter("start", start)
        .setParameter("end", end)
        .getSingleResult();
  }

  /** 登录用户查询资源；完整查询区间计算真实可用性，缺少任一参数拒绝。 */
  @Transactional(readOnly = true)
  public Object rooms(LocalDate date, LocalTime start, LocalTime end) {
    boolean filtered = date != null || start != null || end != null;
    require(!filtered || (date != null && start != null && end != null), 400, "日期和起止时间必须同时提供");
    if (filtered) validateWindow(date, start, end, true);
    return em
        .createQuery(
            "select r from StudyRoom r where r.deleted=false order by r.roomNo", Room.class)
        .getResultList()
        .stream()
        .map(
            r ->
                roomDto(
                    r,
                    "OPEN".equals(r.status)
                        && (!filtered || conflicts(null, r.id, date, start, end) == 0)))
        .toList();
  }

  /** 查询未删除单个房间，不泄露隐藏资源。 */
  @Transactional(readOnly = true)
  public Object getRoom(long id) {
    Room r = room(id, false);
    require(!r.deleted, 404, "房间已删除");
    return roomDto(r, "OPEN".equals(r.status));
  }

  private boolean active(Room r) {
    LocalDateTime current = now();
    return em
        .createQuery(
            "select o from Booking o where o.room.id=:id and o.status='RESERVED'", Order.class)
        .setParameter("id", r.id)
        .getResultList()
        .stream()
        .anyMatch(o -> o.orderDate.atTime(o.endTime).isAfter(current));
  }

  /** 管理员新增或修改房间；关房与预约共用房间锁，阻止先查后写竞态。 */
  public Object saveRoom(long actor, Long id, RoomInput dto) {
    User a = user(actor, false);
    admin(a);
    Room r = id == null ? new Room() : room(id, true);
    require(!r.deleted, 404, "房间已删除");
    require(
        em.createQuery(
                    "select count(r) from StudyRoom r where r.roomNo=:no and (:id is null or"
                        + " r.id<>:id)",
                    Long.class)
                .setParameter("no", dto.roomNo())
                .setParameter("id", id)
                .getSingleResult()
            == 0,
        409,
        "房间编号已存在");
    if (id != null && "CLOSED".equals(dto.status())) require(!active(r), 409, "存在当前或未来有效预约，不能关闭房间");
    r.roomNo = dto.roomNo();
    r.floor = dto.floor();
    r.capacity = dto.capacity();
    r.status = dto.status();
    r.remark = dto.remark();
    if (id == null) em.persist(r);
    em.flush();
    audit(a, "ROOM_SAVE", "room:" + r.id, "保存房间，状态=" + r.status);
    return roomDto(r, "OPEN".equals(r.status));
  }

  /** 管理员软删除；历史记录继续关联，当前/未来有效预约阻止删除。 */
  public Object deleteRoom(long actor, long id) {
    User a = user(actor, false);
    admin(a);
    Room r = room(id, true);
    require(!r.deleted, 404, "房间已删除");
    require(!active(r), 409, "存在当前或未来有效预约，不能删除房间");
    r.deleted = true;
    r.status = "CLOSED";
    audit(a, "ROOM_DELETE", "room:" + id, "软删除并关闭房间");
    return null;
  }

  /** 学生创建预约；用户锁→房间锁同时防止学生跨房与房间跨学生重叠。 */
  public Object createOrder(long actor, BookingInput dto) {
    User u = user(actor, true);
    student(u);
    Room r = room(dto.roomId(), true);
    require(!u.isBan, 403, "累计违规至少三次，已限制预约");
    require(!r.deleted && "OPEN".equals(r.status), 409, "房间未开放");
    validateWindow(dto.orderDate(), dto.startTime(), dto.endTime(), true);
    require(
        conflicts(u.id, null, dto.orderDate(), dto.startTime(), dto.endTime()) == 0,
        409,
        "学生在该时段已有预约");
    require(
        conflicts(null, r.id, dto.orderDate(), dto.startTime(), dto.endTime()) == 0,
        409,
        "房间在该时段已被预约");
    Order o = new Order();
    o.user = u;
    o.room = r;
    o.orderDate = dto.orderDate();
    o.startTime = dto.startTime();
    o.endTime = dto.endTime();
    o.status = "RESERVED";
    o.attendance = "UNKNOWN";
    o.createdAt = now();
    em.persist(o);
    em.flush();
    audit(u, "BOOKING_CREATE", "order:" + o.id, "创建整间独占预约");
    return orderDto(o);
  }

  private Order lockOrder(long id) {
    // 先读取不可修改的外键确定锁序，再锁学生、房间、订单。房间管理员不再反向获取学生锁。
    Order original = order(id);
    user(original.user.id, true);
    room(original.room.id, true);
    em.lock(original, LockModeType.PESSIMISTIC_WRITE);
    em.refresh(original);
    return original;
  }

  /** 查询本人或管理员全量预约，历史状态完整保留。 */
  @Transactional(readOnly = true)
  public Object orders(long actor, boolean all) {
    User a = user(actor, false);
    if (all) admin(a);
    else student(a);
    TypedQuery<Order> query =
        em.createQuery(
            "select o from Booking o"
                + (all ? "" : " where o.user.id=:id")
                + " order by o.createdAt desc",
            Order.class);
    if (!all) query.setParameter("id", actor);
    return query.getResultList().stream().map(this::orderDto).toList();
  }

  /** 学生开始前取消本人订单，管理员可异常取消；重复同一取消幂等，不取消已完成或爽约。 */
  public Object cancel(long actor, long id, boolean byAdmin, String reason) {
    User a = user(actor, false);
    if (byAdmin) admin(a);
    else student(a);
    Order o = lockOrder(id);
    if (!byAdmin) require(o.user.id.equals(actor), 403, "只能取消本人预约");
    if ("CANCELLED".equals(o.status)) return orderDto(o);
    require("RESERVED".equals(o.status), 409, "该状态不允许取消");
    if (!byAdmin) require(now().isBefore(o.orderDate.atTime(o.startTime)), 409, "预约已开始，不能自行取消");
    o.status = "CANCELLED";
    audit(
        a,
        byAdmin ? "ADMIN_CANCEL" : "STUDENT_CANCEL",
        "order:" + id,
        reason + "；状态RESERVED→CANCELLED");
    return orderDto(o);
  }

  private void recalculate(User u) {
    em.flush();
    u.violateCount =
        em.createQuery(
                "select count(v) from ViolationEvent v where v.user.id=:id and v.revoked=false",
                Long.class)
            .setParameter("id", u.id)
            .getSingleResult()
            .intValue();
    u.isBan = u.violateCount >= 3;
  }

  /** 管理员确认到场、迟到、爽约或完成；证据和状态原子写入，重复相同合法动作幂等。 */
  public Object attendance(long actor, long id, AttendanceInput dto) {
    User a = user(actor, false);
    admin(a);
    Order o = lockOrder(id);
    LocalDateTime current = now();
    String action = dto.action();
    require(!"CANCELLED".equals(o.status), 409, "已取消预约不可确认到场");
    if (("COMPLETE".equals(action) && "COMPLETED".equals(o.status))
        || (!"COMPLETE".equals(action) && action.equals(o.attendance))) return orderDto(o);
    require("RESERVED".equals(o.status), 409, "预约状态不允许此操作");
    require(!current.isBefore(o.orderDate.atTime(o.startTime)), 409, "预约尚未开始");
    String before = o.status + "/" + o.attendance;
    if ("COMPLETE".equals(action)) {
      require(o.arrivedAt != null, 409, "必须先确认到场");
      require(!current.isBefore(o.orderDate.atTime(o.endTime)), 409, "预约尚未结束");
      o.status = "COMPLETED";
      o.completedAt = current;
    } else {
      require("UNKNOWN".equals(o.attendance), 409, "到场证据已存在，请通过违规撤销纠错");
      o.attendance = action;
      if ("NO_SHOW".equals(action)) {
        o.status = "NO_SHOW";
      } else {
        o.arrivedAt = current;
      }
      if ("LATE".equals(action) || "NO_SHOW".equals(action)) {
        List<Violation> prior =
            em.createQuery(
                    "select v from ViolationEvent v where v.order.id=:id and v.revoked=false",
                    Violation.class)
                .setParameter("id", o.id)
                .getResultList();
        // 撤销记录不可复活；重新分类追加新的证据，活动唯一键及订单锁防止重复处罚。
        require(prior.isEmpty(), 409, "该订单已有历史违规证据，不能重复处罚");
        Violation v = new Violation();
        v.user = o.user;
        v.order = o;
        v.activeOrderId = o.id;
        v.type = action;
        v.reason = dto.reason();
        v.occurredAt = current;
        em.persist(v);
        recalculate(o.user);
      }
    }
    audit(
        a,
        "ATTENDANCE_" + action,
        "order:" + id,
        dto.reason() + "；状态" + before + "→" + o.status + "/" + o.attendance);
    return orderDto(o);
  }

  /** 管理员查看学生，服务端直接验证ADMIN，不包含密码。 */
  @Transactional(readOnly = true)
  public Object users(long actor) {
    admin(user(actor, false));
    return em
        .createQuery("select u from CampusUser u where u.role='STUDENT' order by u.id", User.class)
        .getResultList()
        .stream()
        .map(this::userDto)
        .toList();
  }

  /** 查询本人或管理员全量违规，撤销记录也返回以便解释处罚变化。 */
  @Transactional(readOnly = true)
  public Object violations(long actor, boolean all) {
    User a = user(actor, false);
    if (all) admin(a);
    else student(a);
    return em
        .createQuery("select v from ViolationEvent v order by v.occurredAt desc", Violation.class)
        .getResultList()
        .stream()
        .filter(v -> all || v.user.id.equals(actor))
        .map(this::violationDto)
        .toList();
  }

  /** 管理员撤销违规；迟到恢复ARRIVED保留证据时间，爽约恢复RESERVED/UNKNOWN并自动重算封禁。 */
  public Object revoke(long actor, long id, String reason) {
    User a = user(actor, false);
    admin(a);
    Violation v = em.find(Violation.class, id);
    require(v != null, 404, "违规不存在");
    Order o = lockOrder(v.order.id);
    em.lock(v, LockModeType.PESSIMISTIC_WRITE);
    em.refresh(v);
    if (!v.revoked) {
      v.revoked = true;
      v.activeOrderId = null;
      v.revokedAt = now();
      v.revokeReason = reason;
      if ("LATE".equals(v.type)) {
        o.attendance = "ARRIVED";
      } else {
        o.status = "RESERVED";
        o.attendance = "UNKNOWN";
        o.arrivedAt = null;
        o.completedAt = null;
      }
      recalculate(o.user);
      audit(a, "VIOLATION_REVOKE", "violation:" + id, reason);
    }
    return violationDto(v);
  }

  private double minutes(
      LocalDateTime a, LocalDateTime b, LocalDateTime windowStart, LocalDateTime windowEnd) {
    LocalDateTime from = a.isAfter(windowStart) ? a : windowStart,
        to = b.isBefore(windowEnd) ? b : windowEnd;
    return to.isAfter(from) ? Duration.between(from, to).toNanos() / 60_000_000_000.0 : 0;
  }

  private double rate(double minutes, long denominator) {
    return denominator == 0
        ? 0
        : Math.round(Math.min(100.0, 100.0 * minutes / denominator) * 100.0) / 100.0;
  }

  /** 管理员日统计；真实使用仅完成订单的管理员到场/完成时间交集，所有百分比0至100。 */
  @Transactional(readOnly = true)
  public Object statistics(long actor, LocalDate date) {
    admin(user(actor, false));
    LocalDate day = date == null ? now().toLocalDate() : date;
    List<Order> orders =
        em.createQuery("select o from Booking o where o.orderDate=:day", Order.class)
            .setParameter("day", day)
            .getResultList();
    Map<Long, Room> included = new LinkedHashMap<>();
    em.createQuery("select r from StudyRoom r where r.deleted=false order by r.id", Room.class)
        .getResultList()
        .forEach(r -> included.put(r.id, r));
    orders.forEach(o -> included.put(o.room.id, o.room));
    List<Object> rows = new ArrayList<>();
    double totalReserved = 0, totalUsed = 0;
    for (Room r : included.values()) {
      double reserved = 0, used = 0;
      for (Order o : orders) {
        if (!o.room.id.equals(r.id) || "CANCELLED".equals(o.status)) continue;
        LocalDateTime start = day.atTime(o.startTime), end = day.atTime(o.endTime);
        reserved += minutes(start, end, day.atTime(8, 0), day.atTime(22, 0));
        if ("COMPLETED".equals(o.status)
            && ("ARRIVED".equals(o.attendance) || "LATE".equals(o.attendance))
            && o.arrivedAt != null
            && o.completedAt != null) {
          LocalDateTime clippedStart = start.isAfter(day.atTime(8, 0)) ? start : day.atTime(8, 0),
              clippedEnd = end.isBefore(day.atTime(22, 0)) ? end : day.atTime(22, 0);
          used += minutes(o.arrivedAt, o.completedAt, clippedStart, clippedEnd);
        }
      }
      totalReserved += reserved;
      totalUsed += used;
      rows.add(
          map(
              "roomId",
              r.id,
              "roomNo",
              r.roomNo,
              "reservedMinutes",
              reserved,
              "usedMinutes",
              used,
              "availableMinutes",
              840,
              "bookingRate",
              rate(reserved, 840),
              "usageRate",
              rate(used, 840)));
    }
    long violations =
        em.createQuery(
                "select count(v) from ViolationEvent v where v.revoked=false and"
                    + " v.order.orderDate=:day",
                Long.class)
            .setParameter("day", day)
            .getSingleResult();
    return map(
        "date",
        day,
        "totalRooms",
        included.size(),
        "totalOrders",
        orders.stream().filter(o -> !"CANCELLED".equals(o.status)).count(),
        "totalViolations",
        violations,
        "bookingRate",
        rate(totalReserved, included.size() * 840L),
        "usageRate",
        rate(totalUsed, included.size() * 840L),
        "rooms",
        rows);
  }

  /** 显式demo开关才创建合成账号与房间；已有同名数据不覆盖。正式默认关闭。 */
  @Override
  public void run(ApplicationArguments args) {
    if (!demoSeed) return;
    seedUser("admin", "演示管理员", "Admin123!", "ADMIN");
    seedUser("20260001", "演示学生", "Student123!", "STUDENT");
    if (em.createQuery("select count(r) from StudyRoom r", Long.class).getSingleResult() == 0) {
      for (int i = 1; i <= 3; i++) {
        Room r = new Room();
        r.roomNo = "30" + i;
        r.floor = "3楼";
        r.capacity = 30;
        r.status = "OPEN";
        r.remark = "合成演示房间";
        em.persist(r);
      }
    }
  }

  private void seedUser(String no, String name, String password, String role) {
    if (em.createQuery("select count(u) from CampusUser u where u.stuNo=:no", Long.class)
            .setParameter("no", no)
            .getSingleResult()
        == 0) {
      User u = new User();
      u.stuNo = no;
      u.userName = name;
      u.passwordHash = encoder.encode(password);
      u.role = role;
      u.phone = "13800000000";
      em.persist(u);
    }
  }

  /** BCrypt按UTF-8字节而非Java字符限长；提前返回400，防止多字节密码触发框架异常500。 */
  private void validatePasswordBytes(String password) {
    require(password.getBytes(StandardCharsets.UTF_8).length <= 72, 400, "密码UTF-8编码不能超过72字节");
  }
}

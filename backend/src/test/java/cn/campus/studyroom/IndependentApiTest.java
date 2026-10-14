package cn.campus.studyroom;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** DEV-T11/T12独立接口测试：从HTTP和最终数据库状态验证规则，不调用生产服务代替接口。
 * 仅清理隔离测试库的合成数据；H2结果不能冒充MySQL并发或迁移证据。 */
@SpringBootTest @AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("test")
@Import(IndependentApiTest.TimeConfiguration.class)
class IndependentApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired MutableClock clock;
    private String admin, student, other;
    private long studentId, otherId, roomA, roomB;
    private final LocalDate day=LocalDate.of(2026,10,15);

    /** 可选真实MySQL入口必须显式指向含_test的专用库，避免误清正式或演示数据。 */
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url=System.getenv("TEST_DB_URL");
        if(url!=null) {
            if(!url.contains("_test")) throw new IllegalArgumentException("独立测试仅允许_test专用数据库");
            registry.add("spring.datasource.url",()->url);
            registry.add("spring.datasource.username",()->System.getenv("TEST_DB_USERNAME"));
            registry.add("spring.datasource.password",()->System.getenv("TEST_DB_PASSWORD"));
            registry.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
            registry.add("spring.jpa.hibernate.ddl-auto",()->"validate");
            registry.add("spring.flyway.enabled",()->true);
        }
    }
    /** 测试时钟来自服务端依赖注入；请求不伪造到场时间，用固定时刻复现严格边界。 */
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary MutableClock independentClock() { return new MutableClock(); }
    }
    /** 可变Clock仅用于测试，固定上海时区，单个用例先设定时刻再发并发请求。 */
    static class MutableClock extends Clock {
        private volatile Instant current;
        void at(LocalDateTime time) { current=time.atZone(getZone()).toInstant(); }
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(current,zone); }
        @Override public Instant instant() { return current; }
    }
    /** 每例独立准备合成账号、受控管理员和两个整间资源，清理顺序遵守外键。 */
    @BeforeEach void prepare() throws Exception {
        for(String table:List.of("operation_audit","violation_event","login_session","booking","study_room","campus_user")) db.update("delete from "+table);
        clock.at(day.minusDays(1).atTime(12,0));
        JsonNode a=register("qa-admin"),s=register("qa-student"),o=register("qa-other");
        admin=a.path("token").asText();student=s.path("token").asText();other=o.path("token").asText();
        studentId=s.path("user").path("id").asLong();otherId=o.path("user").path("id").asLong();
        db.update("update campus_user set role='ADMIN' where stu_no='qa-admin'");
        // 到场/历史报表用例会推进Clock数天；合成会话延长仅消除鉴权先到期对业务断言的干扰。
        // 生产16小时到期保持原样，由专门的会话到期用例验证。
        db.update("update login_session set expires_at=?",day.plusDays(30).atStartOfDay());
        roomA=createRoom("合成A");roomB=createRoom("合成B");
    }
    /** 统一HTTP辅助只断言状态及success，不输出令牌、密码或完整请求。 */
    private JsonNode call(String method,String path,String token,Object body,int status) throws Exception {
        var request=switch(method){case "GET"->get(path);case "PUT"->put(path);case "DELETE"->delete(path);default->post(path);};
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body!=null)request.contentType("application/json").content(json.writeValueAsString(body));
        MvcResult result=mvc.perform(request).andReturn();
        assertEquals(status,result.getResponse().getStatus(),method+" "+path+" HTTP状态");
        JsonNode response=json.readTree(result.getResponse().getContentAsString());
        assertEquals(status==200,response.path("success").asBoolean());
        if(status!=200)assertTrue(response.path("data").isNull());
        return response.path("data");
    }
    private JsonNode register(String no) throws Exception { return call("POST","/api/auth/register",null,Map.of("stuNo",no,"userName","合成测试","password","Synthetic123!","phone",""),200); }
    private long createRoom(String no) throws Exception { return call("POST","/api/admin/rooms",admin,Map.of("roomNo",no,"floor","1楼","capacity",40,"status","OPEN","remark","合成独立测试"),200).path("id").asLong(); }
    private Map<String,Object> booking(long room,String start,String end) { return Map.of("roomId",room,"orderDate",day.toString(),"startTime",start,"endTime",end); }
    private long book(String token,long room,String start,String end) throws Exception { return call("POST","/api/orders",token,booking(room,start,end),200).path("id").asLong(); }
    private JsonNode act(long id,String action,int status) throws Exception { return call("POST","/api/admin/orders/"+id+"/attendance",admin,Map.of("action",action,"reason","管理员合成核实证据"),status); }
    private int count(String sql,Object...args) { return db.queryForObject(sql,Integer.class,args); }

    /** A01/A07/A10/A11：客户端非法身份字段不可提权；哈希保存但任何公开资料不得泄露。 */
    @Test void authenticationAndOwnership() throws Exception {
        call("GET","/api/auth/me",null,null,401);
        call("GET","/api/admin/users",student,null,403);
        call("POST","/api/auth/register",null,Map.of("stuNo","qa-injected","userName","伪造","password","Synthetic123!","role","ADMIN"),400);
        register("qa-new");call("POST","/api/auth/register",null,Map.of("stuNo","qa-new","userName","重复","password","Synthetic123!"),409);
        JsonNode me=call("GET","/api/auth/me",student,null,200);
        assertEquals("STUDENT",me.path("role").asText());assertFalse(me.has("password"));assertFalse(me.has("passwordHash"));assertFalse(me.has("password_hash"));
        String hash=db.queryForObject("select password_hash from campus_user where id=?",String.class,studentId);
        assertTrue(hash.startsWith("$2"));assertNotEquals("Synthetic123!",hash);
        long order=book(other,roomA,"10:00","11:00");
        call("POST","/api/orders/"+order+"/cancel",student,null,403);
        assertEquals(0,call("GET","/api/orders/me",student,null,200).size());
        call("POST","/api/auth/logout",student,null,200);call("GET","/api/auth/me",student,null,401);
    }
    /** A03/A04/A14：半开边界允许相邻，两维所有重叠形态均拒绝，过期/窗口/期限非法不写库。 */
    @Test void overlapAndTimeBoundaries() throws Exception {
        book(student,roomA,"10:00","11:00");book(student,roomA,"11:00","12:00");
        for(String[] interval:List.of(new String[]{"10:00","11:00"},new String[]{"09:00","12:00"},new String[]{"10:15","10:45"},new String[]{"09:30","10:30"})) call("POST","/api/orders",other,booking(roomA,interval[0],interval[1]),409);
        call("POST","/api/orders",student,booking(roomB,"10:30","11:30"),409);
        for(String[] interval:List.of(new String[]{"10:00","10:00"},new String[]{"11:00","10:00"},new String[]{"07:59","09:00"},new String[]{"21:00","22:01"},new String[]{"22:00","08:00"}))call("POST","/api/orders",other,booking(roomB,interval[0],interval[1]),400);
        var past=new HashMap<>(booking(roomB,"10:00","11:00"));past.put("orderDate",day.minusDays(2).toString());call("POST","/api/orders",other,past,400);
        past.put("orderDate",day.plusDays(7).toString());call("POST","/api/orders",other,past,400);
        past.put("orderDate",day.plusDays(6).toString());call("POST","/api/orders",other,past,200);
        assertEquals(3,count("select count(*) from booking"));
    }
    /** A13：8个同步请求竞争同一房间，不能只看HTTP，最终有效行必须恰为一。 */
    @Test void concurrentRoomConflict() throws Exception { race(false); }
    /** A13：同一学生跨两个房间竞争，用户锁须独立于房间锁保证最终只有一单。 */
    @Test void concurrentStudentConflict() throws Exception { race(true); }
    private void race(boolean sameStudent) throws Exception {
        int size=8;ExecutorService pool=Executors.newFixedThreadPool(size);CountDownLatch ready=new CountDownLatch(size),go=new CountDownLatch(1);
        try {
            List<Future<Integer>> futures=new ArrayList<>();
            for(int i=0;i<size;i++){final int index=i;futures.add(pool.submit(()->{ready.countDown();go.await();var request=post("/api/orders").header("Authorization","Bearer "+(sameStudent?student:index%2==0?student:other)).contentType("application/json").content(json.writeValueAsString(booking(sameStudent&&index%2==1?roomB:roomA,"10:00","11:00")));return mvc.perform(request).andReturn().getResponse().getStatus();}));}
            assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();int success=0;
            for(Future<Integer> future:futures){int status=future.get(30,TimeUnit.SECONDS);assertTrue(status==200||status==409,"并发只应成功或冲突");if(status==200)success++;}
            assertEquals(1,success);assertEquals(1,count("select count(*) from booking where status<>'CANCELLED'"));
        } finally {go.countDown();pool.shutdownNow();}
    }
    /** A05/A15/A16：本人取消幂等仅写一次审计并释放；有关联未来预约资源不得关闭删除。 */
    @Test void cancelAndRoomHistory() throws Exception {
        long id=book(student,roomA,"10:00","11:00");
        call("PUT","/api/admin/rooms/"+roomA,admin,Map.of("roomNo","合成A","floor","1楼","capacity",40,"status","CLOSED"),409);
        call("DELETE","/api/admin/rooms/"+roomA,admin,null,409);
        call("POST","/api/orders/"+id+"/cancel",student,null,200);call("POST","/api/orders/"+id+"/cancel",student,null,200);
        assertEquals(1,count("select count(*) from operation_audit where action='STUDENT_CANCEL'"));book(other,roomA,"10:00","11:00");
        assertEquals(2,count("select count(*) from booking"));
        call("DELETE","/api/admin/rooms/"+roomB,admin,null,200);call("DELETE","/api/admin/rooms/"+roomB,admin,null,404);
    }
    /** A15/A18：恰好开始不能学生取消；开始前拒绝判定，UNKNOWN不能直接完成，到场不能爽约。 */
    @Test void attendanceTimeAndEvidence() throws Exception {
        long id=book(student,roomA,"10:00","11:00");act(id,"ARRIVED",409);
        clock.at(day.atTime(10,0));call("POST","/api/orders/"+id+"/cancel",student,null,409);act(id,"COMPLETE",409);
        JsonNode first=act(id,"ARRIVED",200);clock.at(day.atTime(10,30));assertEquals(first.path("arrivedAt"),act(id,"ARRIVED",200).path("arrivedAt"));
        act(id,"NO_SHOW",409);act(id,"COMPLETE",409);clock.at(day.atTime(11,0));JsonNode complete=act(id,"COMPLETE",200);
        clock.at(day.atTime(12,0));assertEquals(complete.path("completedAt"),act(id,"COMPLETE",200).path("completedAt"));
        assertEquals(0,count("select count(*) from violation_event"));
    }
    /** A18：逾期UNKNOWN仍保留未知；无需自动处罚，读取与报表不产生到場证据。 */
    @Test void unknownNeverBecomesNoShowAutomatically() throws Exception {
        long id=book(student,roomA,"10:00","11:00");clock.at(day.plusDays(2).atTime(12,0));
        assertEquals("UNKNOWN",call("GET","/api/orders/me",student,null,200).get(0).path("attendance").asText());
        act(id,"COMPLETE",409);assertEquals(0,count("select count(*) from violation_event"));
        assertEquals(0,call("GET","/api/admin/statistics?date="+day,admin,null,200).path("usageRate").asDouble());
    }
    /** A19/A20：第三条活动证据封禁，重试不重复计数，撤销保留历史并恢复正确状态。 */
    @Test void violationsThresholdAndRevoke() throws Exception {
        long[] ids={book(student,roomA,"08:00","09:00"),book(student,roomA,"09:00","10:00"),book(student,roomA,"10:00","11:00")};
        clock.at(day.atTime(11,0));act(ids[0],"LATE",200);act(ids[0],"LATE",200);act(ids[1],"NO_SHOW",200);act(ids[2],"NO_SHOW",200);act(ids[2],"NO_SHOW",200);
        assertEquals(3,count("select count(*) from violation_event where revoked=false"));
        assertTrue(call("GET","/api/auth/me",student,null,200).path("isBan").asBoolean());
        var future=new HashMap<>(booking(roomB,"12:00","13:00"));future.put("orderDate",day.plusDays(1).toString());call("POST","/api/orders",student,future,403);
        long violation=db.queryForObject("select id from violation_event where order_id=?",Long.class,ids[2]);
        call("POST","/api/admin/violations/"+violation+"/revoke",admin,Map.of("reason","合成纠错"),200);call("POST","/api/admin/violations/"+violation+"/revoke",admin,Map.of("reason","重试"),200);
        assertEquals(2,call("GET","/api/auth/me",student,null,200).path("violateCount").asInt());assertFalse(call("GET","/api/auth/me",student,null,200).path("isBan").asBoolean());
        assertEquals("RESERVED",db.queryForObject("select status from booking where id=?",String.class,ids[2]));assertEquals("UNKNOWN",db.queryForObject("select attendance from booking where id=?",String.class,ids[2]));
        long late=db.queryForObject("select id from violation_event where order_id=?",Long.class,ids[0]);
        call("POST","/api/admin/violations/"+late+"/revoke",admin,Map.of("reason","迟到误判"),200);
        assertEquals("ARRIVED",db.queryForObject("select attendance from booking where id=?",String.class,ids[0]));assertNotNull(db.queryForObject("select arrived_at from booking where id=?",LocalDateTime.class,ids[0]));
        assertEquals(3,count("select count(*) from violation_event"));assertEquals(2,count("select count(*) from operation_audit where action='VIOLATION_REVOKE'"));
    }
    /** A21：人工120+60分钟算例；取消不计，违规按预约日期，关闭/删除仍保留历史分母。 */
    @Test void statisticsManualExampleAndHistory() throws Exception {
        long a=book(student,roomA,"09:00","11:00"),b=book(other,roomB,"12:00","13:00"),cancel=book(student,roomA,"14:00","15:00");
        call("POST","/api/orders/"+cancel+"/cancel",student,null,200);
        clock.at(day.atTime(10,0));act(a,"ARRIVED",200);clock.at(day.atTime(11,0));act(a,"COMPLETE",200);clock.at(day.plusDays(1).atTime(10,0));act(b,"NO_SHOW",200);
        call("DELETE","/api/admin/rooms/"+roomA,admin,null,200);
        JsonNode stats=call("GET","/api/admin/statistics?date="+day,admin,null,200);
        assertEquals(2,stats.path("totalRooms").asInt());assertEquals(2,stats.path("totalOrders").asInt());assertEquals(1,stats.path("totalViolations").asInt());assertEquals(10.71,stats.path("bookingRate").asDouble());assertEquals(3.57,stats.path("usageRate").asDouble());
        assertEquals(3,call("GET","/api/admin/orders",admin,null,200).size());
    }
    /** A21：关闭但无订单房间也参与配置开放分母；日期必填，防止静默查询今天。 */
    @Test void closedRoomDenominatorAndRequiredDate() throws Exception {
        call("PUT","/api/admin/rooms/"+roomB,admin,Map.of("roomNo","合成B","floor","1楼","capacity",40,"status","CLOSED"),200);
        assertEquals(2,call("GET","/api/admin/statistics?date="+day,admin,null,200).path("totalRooms").asInt());
        call("GET","/api/admin/statistics",admin,null,400);
    }
    /** A21：秒级到场时刻必须最后统一求分钟/百分比，不能每单丢掉30秒。 */
    @Test void secondPrecisionUsage() throws Exception {
        long id=book(student,roomA,"10:00","11:00");clock.at(day.atTime(10,0,30));act(id,"ARRIVED",200);clock.at(day.atTime(11,0));act(id,"COMPLETE",200);
        JsonNode stats=call("GET","/api/admin/statistics?date="+day,admin,null,200);
        JsonNode row=null;for(JsonNode r:stats.path("rooms"))if(r.path("roomId").asLong()==roomA)row=r;
        assertNotNull(row);assertEquals(59.5,row.path("usedMinutes").asDouble());assertEquals(3.54,stats.path("usageRate").asDouble());
    }
    /** A10/A18：客户端不能回填到场/完成时间，空原因不能写证据或审计。 */
    @Test void attendanceReasonAndTimeInjection() throws Exception {
        long id=book(student,roomA,"10:00","11:00");clock.at(day.atTime(10,0));
        call("POST","/api/admin/orders/"+id+"/attendance",admin,Map.of("action","ARRIVED","reason"," "),400);
        call("POST","/api/admin/orders/"+id+"/attendance",admin,Map.of("action","ARRIVED","reason","伪造时间","arrivedAt",day.atTime(9,0).toString()),400);
        assertEquals("UNKNOWN",db.queryForObject("select attendance from booking where id=?",String.class,id));assertEquals(0,count("select count(*) from operation_audit where action like 'ATTENDANCE_%'"));
    }
    /** A07：独立验证生产真实16小时会话到期，避免长业务时间场景的fixture掩盖鉴权缺陷。 */
    @Test void sessionExpiresAtSixteenHours() throws Exception {
        String token=register("qa-expiry").path("token").asText();
        clock.at(day.atTime(3,59));call("GET","/api/auth/me",token,null,200);
        clock.at(day.atTime(4,0));call("GET","/api/auth/me",token,null,401);
    }
    /** A19：同单8个并发迟到判定仅保留一次违规、一次审计与首次证据时间。 */
    @Test void concurrentViolationIsIdempotent() throws Exception {
        long id=book(student,roomA,"10:00","11:00");clock.at(day.atTime(10,0));
        ExecutorService pool=Executors.newFixedThreadPool(8);CountDownLatch go=new CountDownLatch(1);
        try {
            List<Future<JsonNode>> futures=new ArrayList<>();
            for(int i=0;i<8;i++)futures.add(pool.submit(()->{go.await();return act(id,"LATE",200);}));
            go.countDown();JsonNode first=null;
            for(Future<JsonNode> future:futures){JsonNode result=future.get(30,TimeUnit.SECONDS);if(first==null)first=result;assertEquals(first.path("arrivedAt"),result.path("arrivedAt"));}
            assertEquals(1,count("select count(*) from violation_event where revoked=false"));assertEquals(1,count("select violate_count from campus_user where id=?",studentId));assertEquals(1,count("select count(*) from operation_audit where action='ATTENDANCE_LATE'"));
        } finally {go.countDown();pool.shutdownNow();}
    }
    /** A16：关房与新增同时争锁，只有关房胜出或订单胜出两种合法终局，不能关闭后仍留未来单。 */
    @Test void closingRoomAndBookingRace() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch go=new CountDownLatch(1);
        try {
            Future<Integer> close=pool.submit(()->{go.await();return mvc.perform(put("/api/admin/rooms/"+roomA).header("Authorization","Bearer "+admin).contentType("application/json").content(json.writeValueAsString(Map.of("roomNo","合成A","floor","1楼","capacity",40,"status","CLOSED")))).andReturn().getResponse().getStatus();});
            Future<Integer> order=pool.submit(()->{go.await();return mvc.perform(post("/api/orders").header("Authorization","Bearer "+student).contentType("application/json").content(json.writeValueAsString(booking(roomA,"10:00","11:00")))).andReturn().getResponse().getStatus();});
            go.countDown();int c=close.get(30,TimeUnit.SECONDS),o=order.get(30,TimeUnit.SECONDS);
            assertTrue((c==200&&o==409)||(c==409&&o==200));
            String status=db.queryForObject("select status from study_room where id=?",String.class,roomA);
            assertEquals("CLOSED".equals(status)?0:1,count("select count(*) from booking where room_id=? and status='RESERVED'",roomA));
        } finally {go.countDown();pool.shutdownNow();}
    }
    /** A19/A22：只在专用测试库注入审计约束故障，使最后一步写入失败，核对全部业务状态回滚后可重试。
     * 该测试不修改生产代码，finally移除临时约束；模拟数据库异常而非伪造服务返回。 */
    @Test void attendanceTransactionRollsBackOnAuditFailure() throws Exception {
        long id=book(student,roomA,"10:00","11:00");clock.at(day.atTime(10,0));
        db.execute("alter table operation_audit add constraint qa_fail_audit check (action <> 'ATTENDANCE_LATE')");
        try {
            act(id,"LATE",500);
            assertEquals("UNKNOWN",db.queryForObject("select attendance from booking where id=?",String.class,id));
            assertNull(db.queryForObject("select arrived_at from booking where id=?",LocalDateTime.class,id));
            assertEquals(0,count("select count(*) from violation_event"));assertEquals(0,count("select violate_count from campus_user where id=?",studentId));
        } finally {
            try(var connection=db.getDataSource().getConnection()) {
                if(connection.getMetaData().getDatabaseProductName().equals("MySQL"))db.execute("alter table operation_audit drop check qa_fail_audit");
                else db.execute("alter table operation_audit drop constraint qa_fail_audit");
            }
        }
        act(id,"LATE",200);assertEquals(1,count("select count(*) from violation_event"));
    }
    /** A10：BCrypt以UTF-8字节为边界，24汉字72字节合法，25汉字75字节注册及登录均400。
     * 不允许底层截断密码形成等价口令；成功响应及数据库存储仍不得出现明文密码。 */
    @Test void bcryptUtf8ByteBoundary() throws Exception {
        String allowed="汉".repeat(24),tooLong="汉".repeat(25);
        assertEquals(72,allowed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        JsonNode created=call("POST","/api/auth/register",null,Map.of("stuNo","qa-utf8","userName","字节边界","password",allowed),200);
        assertFalse(created.path("user").has("password"));assertFalse(created.path("user").has("passwordHash"));
        call("POST","/api/auth/login",null,Map.of("stuNo","qa-utf8","password",allowed),200);
        call("POST","/api/auth/register",null,Map.of("stuNo","qa-utf8-too-long","userName","超限","password",tooLong),400);
        call("POST","/api/auth/login",null,Map.of("stuNo","qa-utf8","password",tooLong),400);
        assertEquals(0,count("select count(*) from campus_user where stu_no='qa-utf8-too-long'"));
        String hash=db.queryForObject("select password_hash from campus_user where stu_no='qa-utf8'",String.class);
        assertTrue(hash.startsWith("$2"));assertFalse(hash.contains(allowed));
    }
}

# 校园自习室预约系统：宝塔生产部署

目标：https://www.zhangbowen666.cn；Vue2 + Spring Boot3 / Java21 / MySQL8。当前只完成生产包及本机验证，没有登录或修改线上宝塔。HTTPS证书及强制HTTPS由用户现有宝塔配置管理。

## 1. 本次修改点与前端代码

原项目使用fetch、hash路由；现已改为Axios全局实例与history。保持学生/管理员权限、Bearer会话、统一JSON错误和30人并发业务。生产API网址为公开配置，不放数据库密码到VITE变量。

```js
// frontend/src/api.js：全局API入口，开发默认/api，构建读取生产环境变量。
const httpClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 20000
})
// 请求统一附加Authorization: Bearer <token>；401清理会话，业务错误保留HTTP状态。
// frontend/src/router.js：原hash改history。
new VueRouter({ mode: 'history', base: import.meta.env.BASE_URL, routes })
```

`frontend/.env.production`：

```dotenv
VITE_API_BASE_URL=https://www.zhangbowen666.cn/api
```

Vite生产关键配置如下（完整文件为frontend/vite.config.js）：

```js
base: '/',
build: { outDir: 'dist', emptyOutDir: true, sourcemap: false, minify: 'esbuild' },
// 仅在build移除，开发调试保留；依赖输出的console也移除。
esbuild: command === 'build' ? { drop: ['console', 'debugger'] } : undefined
```

Vite压缩JS/CSS，Nginx gzip压缩传输。实际Axios版本与完整构建结果见打包验证记录，不把旧fetch代码复制到新项目。网址在构建时写入JS，修改域名需要重新build。

## 2. 后端生产配置

普通JAR只包含MySQL/Flyway与生产配置，H2为测试依赖，test配置在测试资源目录，demo配置不进入普通JAR。`-Pdemo`属于明确选择的本地内存演示包，禁止上传该包到生产。

`backend/application-prod.yml`提供完整可用示例；关键配置：

```yaml
server:
  address: 127.0.0.1
  port: 8080
  forward-headers-strategy: framework
spring:
  datasource:
    url: ${DB_URL:jdbc:mysql://127.0.0.1:3306/study_room_reserve?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai}
    username: ${DB_USERNAME:study_room}
    password: ${DB_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    open-in-view: false
    show-sql: false
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    clean-disabled: true
logging:
  level:
    root: INFO
    org.hibernate.SQL: 'OFF'
    org.hibernate.orm.jdbc.bind: 'OFF'
app:
  demo-seed: false
  cors:
    allowed-origins: https://www.zhangbowen666.cn
```

项目使用Spring Security Crypto处理BCrypt，认证本身是MVC拦截器与数据库会话；跨域白名单实际由WebMvcConfigurer读取上面配置，不存在一条虚构的Spring Security FilterChain。Nginx同源代理下通常不产生跨域，但服务端仍只允许指定生产来源。

## 3. 构建命令与产物

需要JDK21、Maven3.9.9、Node24.19.0/npm11.6.2。在仓库根目录先停止本项目前后端，避免Windows运行中的esbuild锁文件：

```powershell
.\stop.bat
cd frontend
npm ci
npm run check
npm run build
cd ..\backend
mvn clean package -DskipTests
```

如果本机没有全局npm/Maven，可以用项目自带解析器运行等价命令：

```powershell
# 根目录一键构建并整理发布包；默认拒绝覆盖已有非空输出目录。
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/package-baota.ps1
```

前端为`dist/`；上传的是dist内部的index.html/assets等全部文件，网站根目录应直接包含index.html，不能再套一层dist。后端原始产物为`backend/target/study-room-0.3.0.jar`，发布包内位于`backend/`。`.jar.original`不是可执行发布包，不上传。`-DskipTests`真实跳过单元测试，独立24项验证是另外执行的，不冒充打包命令运行了测试。

输出目录结构：

```text
校园自习室预约系统打包/
  dist/                       上传网站根目录内部
  backend/
    study-room-0.3.0.jar       宝塔Java项目选择此JAR
    application-prod.yml      外部配置示例，JAR内部也已带prod配置
    production.env.example    只含模板，不包含真实凭据
  nginx/
    nginx-locations.conf      推荐合并进现有宝塔server
    nginx-https-server.conf   完整443 server参考
  README-宝塔部署.md
  生产打包独立测试记录.md
  构建与代理验证记录.md
  SHA256SUMS.txt
  校园自习室预约系统-生产发布.zip
```

## 4. 宝塔完整Nginx配置

`nginx/nginx-https-server.conf`是完整443 server配置，直接可读/复制；证书与网站根目录按宝塔常用路径填入，必须核对现有站点实际路径。由于用户已有SSL和强制HTTPS，推荐把`nginx/nginx-locations.conf`中的完整功能段合并进当前HTTPS server，替换旧`location /`和冲突的代理/缓存段；保留原80跳转、SSL及证书续期的ACME验证include，不替换整个站点文件。旧伪静态可能冲突，先备份再清除Vue以外的旧规则。

关键语义：`proxy_pass http://127.0.0.1:8080;`末尾没有`/`，因此后端仍接收到`/api/orders?...`；若误写成`...8080/`会剥掉匹配的`/api/`前缀，造成接口404。`^~ /api/`阻止静态正则抢走API；API错误直接来自后端，不回退index。history使用`try_files $uri $uri/ /index.html;`，缺失assets返回404而非HTML。

官方依据：[Nginx proxy_pass](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass)、[Vue Router3 history](https://v3.router.vuejs.org/guide/essentials/history-mode.html)、[Vite构建选项](https://vite.dev/config/build-options.html)、[Spring Boot外部配置](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html)。

## 5. 宝塔部署步骤

1. 备份当前网站配置、静态文件和MySQL；旧库有Flyway历史时不要手动覆盖已应用V1/V2/V3或改校验和。新库先在宝塔创建`study_room_reserve`，字符集utf8mb4，只给应用账号该数据库所需迁移权限。
2. 把dist内部全部文件上传实际网站根目录，默认示例为`/www/wwwroot/www.zhangbowen666.cn/`；确认根目录直接能看到index.html与assets。
3. 把JAR上传独立目录，如`/www/wwwroot/study-room-backend/`，不要放进公开网站根目录。用Java项目管理器选JDK21，运行此JAR；不要选Java8/17。
4. 设置环境变量`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`为服务器真实MySQL地址/端口/库名/账号/口令（URL含&必须整体填写，不是本机3307）。添加`--spring.profiles.active=prod`启动参数；或设置`SPRING_PROFILES_ACTIVE=prod`。日志必须显示prod。不要设置demo/test profile。
5. 若面板版本不能设置环境变量，把复制的application-prod.yml放到JAR同目录，将占位值替换为服务器真实信息，文件权限限制应用账号可读，在工作目录与JAR目录一致情况下加`--spring.config.additional-location=file:/www/wwwroot/study-room-backend/`。不提交该已填写文件，不把密码放在公开根目录。
6. 启动命令等价为：`java -jar /www/wwwroot/study-room-backend/study-room-0.3.0.jar --spring.profiles.active=prod`。首次启动Flyway自动建表/迁移，随后JPA validate；端口仅127.0.0.1:8080，无需公网开放8080。面板配置自动重启/开机启动。
7. 合并Nginx功能段，保留已有有效SSL/强制HTTPS/续期规则。实际服务器执行`/www/server/nginx/sbin/nginx -t`，成功后在宝塔重载Nginx，失败时修路径/重复location而不是强行重载。
8. 生产关闭合成演示账号初始化。新库先通过注册页创建你自己的账号并设置密码，再由有权限的数据库管理员仅对该学号执行`UPDATE campus_user SET role='ADMIN' WHERE stu_no='你的管理员学号' AND role='STUDENT';`，重新登录；注册接口始终不能提权。不要把公开演示密码作为生产管理员密码。若导入旧库含演示账号，部署前另行处理其账号安全。

## 6. 上线检查清单

- 浏览器打开https域名，证书有效，无Mixed Content；既有http强制HTTPS正常，ACME续期规则仍在。
- 地址不含#；直接打开`/login`、`/rooms`、`/admin/orders`及刷新均由SPA入口加载，实际页面仍根据登录角色跳转。
- 浏览器Network中接口为`https://www.zhangbowen666.cn/api/...`，没有localhost/8080，没有重复/api/api；生产JS无console/debugger和.map。
- 服务器`curl http://127.0.0.1:8080/api/health`与公网`curl https://www.zhangbowen666.cn/api/health`均JSON UP；匿名`/api/orders/me`为401 JSON，未知API不是200 HTML。
- 带日期/起止参数的房间接口保留原参数；登录返回后Bearer能调用本人记录；账号不能越权。请求生产Origin允许，其他跨域来源被拒绝；Nginx不重复加Access-Control-Allow-Origin。
- Java为21，日志为prod/INFO，Flyway V1—V3成功，无H2、demo账号自动初始化或DB口令回显；重启后预约保留。
- 房间固定30；预约后剩29、取消恢复30；满30拒绝新增；管理员到场、违规及人分钟统计正常。24项并发功能验证不等于公网压力测试。
- index不长期缓存，hash assets可缓存并gzip传输；缺失assets返回404而不是HTML；确认静态文件读权限。
- 502时先查Java进程/8080/启动日志/数据库；history404先查root与location；接口404先查proxy_pass是否错误加尾斜杠；密码/迁移失败先查prod与DB_*。

本机验证与实际宝塔上线验证分开记录。未连接实际服务器，没有宣称公网域名或真实证书已经验收；具体已执行结果见随包验证记录。

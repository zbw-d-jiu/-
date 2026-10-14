"""PKG-T04生产产物独立检查，只读取dist/JAR及可选本地HTTP，不部署或访问公网。

输入--jar、--dist为真实构建产物；--url只允许本地回环服务。
输出逐项PASS/FAIL及安全摘要，不输出请求令牌、密码、连接凭据或本机绝对路径。
任一失败退出1；没有执行的HTTP及Nginx服务器检查不能据此报告通过。
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import urllib.error
import urllib.parse
import urllib.request
import zipfile


def check(condition, description):
    """记录可观察断言；失败收集后仍完成独立检查，最后统一非0退出。"""
    global failures
    print(f"{'PASS' if condition else 'FAIL'} {description}")
    if not condition:
        failures += 1


def request(base, path, method="GET", headers=None, body=None):
    """仅向用户交接的回环服务发请求，返回状态/头/JSON；不记录认证秘密。"""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method,
                                 headers={"Content-Type": "application/json", **(headers or {})})
    try:
        response = urllib.request.urlopen(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        try:
            parsed = json.loads(raw)
        except (ValueError, UnicodeError):
            parsed = None
        return response.status, response.headers, parsed


def scan_jar(path):
    """检查实际Boot JAR而非仅POM：依赖、资源、迁移、业务class主版本与prod约束。"""
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        libs = [n for n in names if n.startswith("BOOT-INF/lib/")]
        check(not any(re.search(r"/h2-[^/]+\.jar$", n) for n in libs), "JAR无H2运行依赖")
        check(any("mysql-connector-j-" in n for n in libs), "JAR含MySQL驱动")
        for lib in ("flyway-core-", "flyway-mysql-"):
            check(any(lib in n for n in libs), "JAR含" + lib.rstrip("-"))
        for config in ("application-test.yml", "application-demo.yml"):
            check(not any(n.endswith(config) for n in names), "JAR无" + config)
        prod_name = "BOOT-INF/classes/application-prod.yml"
        check(prod_name in names, "JAR含application-prod.yml")
        if prod_name in names:
            prod = jar.read(prod_name).decode("utf-8")
            for expected in ("address: 127.0.0.1", "port: 8080", "root: INFO", "demo-seed: false",
                             "allowed-origins: https://www.zhangbowen666.cn", "password: ${DB_PASSWORD}"):
                check(expected in prod, "prod配置约束" + expected.split(":")[0])
        migrations = [Path(n).name for n in names if "/db/migration/" in n and n.endswith(".sql")]
        check(all(any(n.startswith(f"V{i}__") for n in migrations) for i in (1, 2, 3)), "JAR保留V1/V2/V3迁移")
        classes = [n for n in names if n.startswith("BOOT-INF/classes/cn/campus/") and n.endswith(".class")]
        check(bool(classes) and all(struct.unpack(">H", jar.read(n)[6:8])[0] == 65 for n in classes),
              "全部业务class major=65（Java21）")
    print("JAR_SHA256 " + hashlib.sha256(path.read_bytes()).hexdigest())


def scan_dist(path):
    """扫描实际所有JS资产与入口；不将生产源码配置存在误认为产物通过。"""
    check((path / "index.html").is_file(), "dist有index.html")
    files = list(path.rglob("*"))
    check(not any(n.suffix == ".map" for n in files), "dist无.map文件")
    scripts = [n for n in files if n.suffix == ".js"]
    check(bool(scripts), "dist有JS资产")
    merged = "\n".join(n.read_text(encoding="utf-8") for n in scripts)
    check("https://www.zhangbowen666.cn/api" in merged, "实际JS含官方HTTPS API基址")
    check(not re.search(r"\bconsole\s*\.\s*\w+\s*\(", merged), "实际JS无console调用")
    check(not re.search(r"\bdebugger\s*;", merged), "实际JS无debugger语句")
    check("sourceMappingURL=" not in merged, "实际JS无sourceMappingURL")
    check("localhost:8080" not in merged and "127.0.0.1:8080" not in merged, "实际JS无本地后端基址")
    print("DIST_JS_FILES " + str(len(scripts)))


def http_checks(base):
    """实测本地prod HTTP健康、鉴权与精确CORS；只用无副作用的非法提权注册请求。"""
    parsed = urllib.parse.urlparse(base)
    if parsed.hostname not in ("127.0.0.1", "localhost", "::1"):
        raise ValueError("禁止使用公网URL，本脚本仅允许本地回环")
    code, _, body = request(base, "/api/health")
    check(code == 200 and body and body.get("success") is True, "prod健康HTTP200")
    code, _, body = request(base, "/api/auth/me")
    check(code == 401 and body and body.get("success") is False, "保护接口匿名401")
    for origin, permitted in (("https://www.zhangbowen666.cn", True),
                              ("https://evil.example", False), ("http://localhost:5173", False)):
        code, headers, _ = request(base, "/api/auth/me", "OPTIONS", {
            "Origin": origin, "Access-Control-Request-Method": "GET",
            "Access-Control-Request-Headers": "authorization,content-type"})
        allowed = headers.get("Access-Control-Allow-Origin")
        check((code == 200 and allowed == origin) if permitted else (code == 403 and allowed is None),
              "CORS预检" + ("官方允许" if permitted else "非授权来源拒绝") + ("localhost" if "localhost" in origin else ""))
    code, _, body = request(base, "/api/auth/register", "POST", body={
        "stuNo": "qa-prod-role-injection", "userName": "合成非法提权",
        "password": "Synthetic123!", "role": "ADMIN"})
    check(code == 400 and body and body.get("success") is False, "匿名注册未知role400不可提权")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--dist", type=Path, required=True)
    parser.add_argument("--url", help="可选本地prod服务，例如http://127.0.0.1:8080")
    args = parser.parse_args()
    failures = 0
    scan_jar(args.jar)
    scan_dist(args.dist)
    if args.url:
        http_checks(args.url.rstrip("/"))
    else:
        print("NOT_RUN 本地prod HTTP检查（没有指定--url）")
    print("NOT_RUN 公网部署、证书及服务器Nginx语法/行为验证")
    print("FAILURES " + str(failures))
    raise SystemExit(1 if failures else 0)

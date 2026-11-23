"""Run deployment HTTP checks against an explicitly supplied HTTPS server."""
import argparse
import datetime
from html.parser import HTMLParser
import http.cookiejar
import json
from pathlib import Path
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid


parser = argparse.ArgumentParser()
parser.add_argument("--base-url", required=True)
parser.add_argument("--ca-file", required=True)
parser.add_argument("--credentials-file", required=True, help="JSON file path, or - to read credentials from stdin")
parser.add_argument("--report", required=True)
parser.add_argument("--initialize-ledger", action="store_true")
parser.add_argument("--ledger-key-file")
args = parser.parse_args()
base = args.base_url.rstrip("/")
if urllib.parse.urlsplit(base).scheme != "https":
    parser.error("Checks require the configured HTTPS entry point")
report_path = Path(args.report)
if report_path.exists():
    parser.error("Refusing to overwrite an earlier inspection report")
credentials = (json.load(sys.stdin) if args.credentials_file == "-"
               else json.loads(Path(args.credentials_file).read_text(encoding="utf-8")))
context = ssl.create_default_context(cafile=args.ca_file)
cookies = http.cookiejar.CookieJar()
client = urllib.request.build_opener(
    urllib.request.ProxyHandler({}),
    urllib.request.HTTPCookieProcessor(cookies),
    urllib.request.HTTPSHandler(context=context),
)
report = {"checkedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
          "baseUrl": base, "checks": [], "passed": False}
ledger_key_path = (Path(args.ledger_key_file) if args.ledger_key_file
                   else report_path.with_name("ledger-initialization-key"))


def initialization_key(allow_create):
    if ledger_key_path.is_symlink():
        raise RuntimeError("Ledger initialization key must be a regular local file")
    if not ledger_key_path.exists():
        if not allow_create:
            raise RuntimeError("Existing ledger requires its original initialization key")
        # 请求前落盘；网络结果未知时必须复用原键，不能生成第二个初始化意图。
        with ledger_key_path.open("x", encoding="utf-8") as file:
            file.write(str(uuid.uuid4()) + "\n")
    key = ledger_key_path.read_text(encoding="utf-8").strip()
    if str(uuid.UUID(key)) != key:
        raise RuntimeError("Invalid persisted ledger initialization key")
    return key


def request(path, expected=200, method="GET", body=None, headers=None, form=None):
    values = dict(headers or {})
    payload = None
    if body is not None:
        payload = json.dumps(body).encode("utf-8")
        values["Content-Type"] = "application/json"
    if form is not None:
        payload = urllib.parse.urlencode(form).encode("utf-8")
        values["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(base + path, data=payload, headers=values, method=method)
    try:
        response = client.open(req, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        content = response.read(2_000_001)
        if len(content) > 2_000_000:
            raise RuntimeError("Inspection response exceeded bound")
        status = response.status
        record = {"path": path, "method": method, "status": status, "expected": expected}
        report["checks"].append(record)
        print(json.dumps(record), flush=True)
        if status != expected:
            raise RuntimeError(f"Unexpected HTTP status for {method} {path}: {status}")
        return content, response.headers


def json_request(path, **kwargs):
    data, headers = request(path, **kwargs)
    return json.loads(data) if data else None, headers


class Resources(HTMLParser):
    def __init__(self):
        super().__init__()
        self.paths = set()

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if tag == "script" and attributes.get("src", "").startswith("/assets/"):
            self.paths.add(attributes["src"])
        if tag == "link" and attributes.get("href", "").startswith("/assets/"):
            self.paths.add(attributes["href"])


try:
    page, headers = request("/")
    if b"<html" not in page.lower() or b"<script" not in page.lower():
        raise RuntimeError("Entry did not return the built HTML")
    resources = Resources()
    resources.feed(page.decode("utf-8"))
    if not resources.paths:
        raise RuntimeError("Entry assets were not found")
    for path in sorted(resources.paths):
        content, resource_headers = request(path)
        if "text/html" in resource_headers.get("Content-Type", ""):
            raise RuntimeError("Asset incorrectly fell back to the HTML entry")
    state, _ = json_request("/api/v1/auth/session")
    if state.get("authenticated") is not False:
        raise RuntimeError("Anonymous session was unexpectedly authenticated")
    request("/api/v1/knowledge/items", expected=401)
    csrf, _ = json_request("/api/v1/auth/csrf")
    request("/api/v1/auth/login", method="POST", expected=204,
            headers={csrf["headerName"]: csrf["token"]}, form=credentials)
    sessions = [cookie for cookie in cookies if cookie.name == "STUDIO_SESSION"]
    if (len(sessions) != 1 or not sessions[0].secure
            or not (sessions[0].has_nonstandard_attr("HttpOnly")
                    or sessions[0].has_nonstandard_attr("httponly"))):
        raise RuntimeError("Expected secure, HttpOnly session cookie")
    state, _ = json_request("/api/v1/auth/session")
    if state.get("authenticated") is not True:
        raise RuntimeError("Login did not establish the owner session")
    csrf, _ = json_request("/api/v1/auth/csrf")
    status, _ = json_request("/api/v1/data-privacy/status")
    if (args.initialize_ledger and status.get("configured") is True
            and status.get("synchronizedWithDatabase") is not True):
        key = initialization_key(allow_create=status.get("initialized") is False)
        status, _ = json_request(
            "/api/v1/data-privacy/ledger/initialize", method="POST",
            headers={csrf["headerName"]: csrf["token"], "Idempotency-Key": key},
            body={"acknowledgeCurrentBaseline": False},
        )
    report["privacy"] = {key: status.get(key) for key in ("configured", "initialized", "synchronizedWithDatabase", "state")}
    if not all(status.get(key) is True for key in ("configured", "initialized", "synchronizedWithDatabase")):
        raise RuntimeError("Privacy ledger is not initialized and synchronized")
    for path in (
        "/api/v1/knowledge/items", "/api/v1/knowledge/graph/state",
        "/api/v1/bank/questions", "/api/v1/ai/settings",
        "/api/v1/prepare/items", "/api/v1/training/sessions", "/api/v1/training/active",
        "/api/v1/today/overview?zoneId=Asia%2FShanghai", "/api/v1/knowledge/search/status",
        "/api/v1/knowledge/search?q=Java",
    ):
        result, _ = json_request(path)
        if path == "/api/v1/ai/settings":
            configuration = result["configuration"]
            report["ai"] = {key: configuration.get(key)
                            for key in ("enabled", "configured", "protocol")}
    # 实际会话的无CSRF写入应拒绝；不创建训练或示例业务内容。
    request("/api/v1/auth/logout", method="POST", expected=403)
    request("/api/v1/auth/logout", method="POST", expected=204,
            headers={csrf["headerName"]: csrf["token"]})
    state, _ = json_request("/api/v1/auth/session")
    if state.get("authenticated") is not False:
        raise RuntimeError("Logout did not invalidate the session")
    report["passed"] = True
except Exception as error:
    report["error"] = f"{type(error).__name__}: {error}"
    print("SMOKE_FAILED=" + report["error"], flush=True)
finally:
    with report_path.open("x", encoding="utf-8") as file:
        json.dump(report, file, ensure_ascii=False, indent=2)
    print("SMOKE_REPORT=" + str(report_path), flush=True)
sys.exit(0 if report["passed"] else 1)

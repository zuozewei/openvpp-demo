# -*- coding: utf-8 -*-
# Mock 模式（离线）冒烟：登录 → 三角色页面渲染 + 控制台零报错
import os
import sys

from playwright.sync_api import sync_playwright

BASE = os.environ.get("ADMIN_BASE", "http://localhost:5175")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "screenshots-mock")
PASSWORD = "Openvpp@2026"


def login(page, account):
    page.goto(f"{BASE}/login", wait_until="networkidle")
    page.fill('input[placeholder="请输入账号"]', account)
    page.fill('input[placeholder="请输入密码"]', PASSWORD)
    page.click('button:has-text("登 录")')
    page.wait_for_load_state("networkidle")


def main():
    os.makedirs(OUT, exist_ok=True)
    errors = []
    with sync_playwright() as p:
        browser = p.chromium.launch()
        context = browser.new_context(viewport={"width": 1600, "height": 950}, locale="zh-CN")
        page = context.new_page()
        page.on("pageerror", lambda e: errors.append(f"pageerror: {e}"))
        page.on("console", lambda m: errors.append(f"console: {m.text}") if m.type == "error" else None)

        page.goto(f"{BASE}/login", wait_until="networkidle")
        page.wait_for_timeout(400)
        page.screenshot(path=os.path.join(OUT, "m01-login.png"))

        login(page, "platform_admin")
        for menu, name in [
            ("事件管理", "m02-platform-events"),
            ("派单管理", "m03-platform-dispatch"),
            ("运行监测", "m04-platform-monitor"),
            ("效果评估", "m05-platform-assessment"),
            ("结算管理", "m06-platform-settlement"),
            ("操作审计", "m07-platform-audit"),
        ]:
            page.click(f'.el-menu-item:has-text("{menu}")')
            page.wait_for_load_state("networkidle")
            page.wait_for_timeout(900)
            page.screenshot(path=os.path.join(OUT, f"{name}.png"))

        page.click('.el-menu-item:has-text("事件管理")')
        page.wait_for_load_state("networkidle")
        first_link = page.locator(".el-table__row .el-link").first
        if first_link.count():
            event_id = first_link.inner_text().strip()
            page.goto(f"{BASE}/platform/events/{event_id}", wait_until="networkidle")
            page.wait_for_timeout(900)
            page.screenshot(path=os.path.join(OUT, "m08-platform-event-detail.png"))

        page.evaluate("localStorage.clear()")
        login(page, "operator_admin")
        page.goto(f"{BASE}/operator/dispatch", wait_until="networkidle")
        page.wait_for_timeout(900)
        page.screenshot(path=os.path.join(OUT, "m09-operator-dispatch.png"))

        page.evaluate("localStorage.clear()")
        login(page, "station_admin")
        page.goto(f"{BASE}/station/declarations", wait_until="networkidle")
        page.wait_for_timeout(900)
        page.screenshot(path=os.path.join(OUT, "m10-station-declarations.png"))

        browser.close()
    print("MOCK_SMOKE_ERRORS:", errors if errors else "none")


if __name__ == "__main__":
    main()

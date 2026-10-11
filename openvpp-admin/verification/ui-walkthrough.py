# -*- coding: utf-8 -*-
# openvpp-admin 第 58 篇 UI 走查：3 演示账号 × 工作台页面，截图存 verification/screenshots/
# 前提：vite dev（VITE_USE_MOCK=false, 5174）+ openvpp-app（8080）已启动；事件标识作为参数传入
import os
import sys

from playwright.sync_api import sync_playwright

BASE = os.environ.get("ADMIN_BASE", "http://localhost:5174")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "screenshots")
PASSWORD = "Openvpp@2026"
EVENT_ID = sys.argv[1] if len(sys.argv) > 1 else ""


def login(page, account):
    page.goto(f"{BASE}/login", wait_until="networkidle")
    try:
        page.wait_for_selector('input[placeholder="请输入账号"]', timeout=60000)
    except Exception:
        print("LOGIN_PAGE_DIAG url=", page.url)
        print("LOGIN_PAGE_DIAG body=", page.locator("body").inner_text()[:300])
        raise
    page.fill('input[placeholder="请输入账号"]', account)
    page.fill('input[placeholder="请输入密码"]', PASSWORD)
    page.click('button:has-text("登 录")')
    page.wait_for_load_state("networkidle")


def shot(page, name):
    page.wait_for_timeout(600)
    page.screenshot(path=os.path.join(OUT, f"{name}.png"))
    print(f"saved {name}.png")


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
        shot(page, "01-login")

        login(page, "platform_admin")
        shot(page, "02-platform-home")
        page.click('.el-menu-item:has-text("事件管理")')
        page.wait_for_load_state("networkidle")
        shot(page, "03-platform-events")
        if EVENT_ID:
            page.goto(f"{BASE}/platform/events/{EVENT_ID}", wait_until="networkidle")
            shot(page, "04-platform-event-detail")
        page.goto(f"{BASE}/platform/dispatch", wait_until="networkidle")
        shot(page, "05-platform-dispatch")
        page.goto(f"{BASE}/platform/monitor", wait_until="networkidle")
        page.wait_for_timeout(1500)
        shot(page, "06-platform-monitor")
        page.goto(f"{BASE}/platform/assessment", wait_until="networkidle")
        shot(page, "07-platform-assessment")
        page.goto(f"{BASE}/platform/settlement", wait_until="networkidle")
        shot(page, "08-platform-settlement")
        first_settlement = page.locator(".el-table__row .el-link").first
        if first_settlement.count():
            first_settlement.click()
            page.wait_for_timeout(1200)
            shot(page, "09-platform-settlement-detail")
            page.keyboard.press("Escape")
            page.wait_for_timeout(400)
        page.goto(f"{BASE}/platform/audit", wait_until="networkidle")
        shot(page, "10-platform-audit")
        page.click(".workspace-user")
        page.wait_for_timeout(300)
        page.click('.el-dropdown-menu__item:has-text("退出登录")')
        page.wait_for_timeout(500)
        # 退出确认弹窗（ElMessageBox.confirm）
        page.click('.el-message-box button:has-text("退出")')
        page.wait_for_timeout(800)

        login(page, "operator_admin")
        shot(page, "11-operator-home")
        page.goto(f"{BASE}/operator/declarations", wait_until="networkidle")
        shot(page, "12-operator-declarations")
        page.goto(f"{BASE}/operator/dispatch", wait_until="networkidle")
        shot(page, "13-operator-dispatch-commands")
        page.goto(f"{BASE}/operator/monitor", wait_until="networkidle")
        page.wait_for_timeout(1500)
        shot(page, "14-operator-monitor")
        page.click(".workspace-user")
        page.wait_for_timeout(300)
        page.click('.el-dropdown-menu__item:has-text("退出登录")')
        page.wait_for_timeout(500)
        # 退出确认弹窗（ElMessageBox.confirm）
        page.click('.el-message-box button:has-text("退出")')
        page.wait_for_timeout(800)

        login(page, "station_admin")
        shot(page, "15-station-home")
        page.goto(f"{BASE}/station/declarations", wait_until="networkidle")
        shot(page, "16-station-declarations")
        page.goto(f"{BASE}/station/settlement", wait_until="networkidle")
        shot(page, "17-station-settlement")

        browser.close()
    print("UI_ERRORS:", errors if errors else "none")


if __name__ == "__main__":
    main()

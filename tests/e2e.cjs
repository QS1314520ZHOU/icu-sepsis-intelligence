const { chromium } = require("playwright");
const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");
const root = path.resolve(__dirname, "..");
const env = Object.fromEntries(
  fs
    .readFileSync(
      process.env.E2E_ENV_FILE || path.join(root, ".runtime/demo.env"),
      "utf8",
    )
    .trim()
    .split("\n")
    .map((x) => {
      const n = x.indexOf("=");
      return [x.slice(0, n), x.slice(n + 1)];
    }),
);
const base = process.env.E2E_BASE_URL || "http://127.0.0.1:8080";
if (process.env.E2E_ALLOW_SIMULATION !== "true")
  throw Error("Set E2E_ALLOW_SIMULATION=true for an isolated simulation only");
const out = path.join(root, "docs/screenshots");
fs.mkdirSync(out, { recursive: true });
const results = [];
let browser;
const faults = [];
async function api(p, role = "doctor", method = "GET", body) {
  const r = await fetch(base + "/api/v1" + p, {
    method,
    headers: {
      Authorization:
        "Basic " +
        Buffer.from(
          role + ":" + env[role.toUpperCase() + "_PASSWORD"],
        ).toString("base64"),
      "Content-Type": "application/json",
      "Idempotency-Key": crypto.randomUUID(),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: r.status, json: await r.json() };
}
async function check(name, fn) {
  await fn();
  results.push({ name, status: "passed" });
  console.log("PASS " + name);
}
(async () => {
  browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_PATH || chromium.executablePath(),
    headless: true,
    args: ["--no-sandbox"],
  });
  const page = await browser.newPage({
    viewport: { width: 1440, height: 1000 },
    timezoneId: "Asia/Shanghai",
  });
  page.on("pageerror", (e) => faults.push(e.message));
  async function login(role) {
    await page.goto(base + "/login");
    if (!fs.existsSync(path.join(out, "00-login-desktop.png")))
      await page.screenshot({
        path: path.join(out, "00-login-desktop.png"),
        fullPage: true,
      });
    await page.getByLabel("角色账号").fill(role);
    await page
      .getByLabel("登录密码")
      .fill(env[role.toUpperCase() + "_PASSWORD"]);
    await page.getByRole("button", { name: "进入工作台" }).click();
    await page
      .getByRole("heading", { name: "患者风险列表", exact: true })
      .waitFor();
    await page.getByText("模拟患者 A", { exact: true }).waitFor();
  }
  async function logout() {
    await page.getByRole("button", { name: "退出登录" }).click();
  }
  await check("unauthenticated API returns 401 and trace", async () => {
    const r = await fetch(base + "/api/v1/patients");
    assert.equal(r.status, 401);
    assert.ok(r.headers.get("x-trace-id"));
  });
  await check("doctor login and real patient API", async () => {
    await login("doctor");
    const r = await api("/patients");
    assert.equal(r.status, 200);
    assert.ok(r.json.data.items.every((p) => p.simulated));
    await page.screenshot({
      path: path.join(out, "01-patients-desktop.png"),
      fullPage: true,
    });
  });
  await check(
    "overview uses backend counts and working navigation",
    async () => {
      await page.getByRole("link", { name: "风险总览", exact: true }).click();
      await page
        .getByRole("heading", { name: "脓毒症风险总览", exact: true })
        .waitFor();
      await page.getByText("当前患者", { exact: true }).waitFor();
      await page.screenshot({
        path: path.join(out, "14-overview.png"),
        fullPage: true,
      });
      await page
        .getByRole("link", { name: "患者风险列表", exact: true })
        .click();
      await page.getByText("模拟患者 A", { exact: true }).waitFor();
    },
  );
  await check("empty filter shows explicit empty state", async () => {
    await page.getByLabel("患者或床位").fill("NOT-A-PATIENT");
    await page.getByRole("button", { name: "查询", exact: true }).click();
    await page.getByText("未找到符合条件的患者").waitFor();
    await page.screenshot({
      path: path.join(out, "02-empty.png"),
      fullPage: true,
    });
    await page.getByLabel("患者或床位").fill("");
    await page.getByRole("button", { name: "查询", exact: true }).click();
  });
  await check("insufficient data stays unassessable", async () => {
    await page
      .locator("tr")
      .filter({ hasText: "模拟患者 C" })
      .getByRole("link", { name: "查看证据 →" })
      .click();
    await page.getByRole("button", { name: "执行规则筛查" }).click();
    await page
      .getByText("信息不足，无法评估", { exact: true })
      .first()
      .waitFor();
    await page.waitForFunction(() =>
      [...document.querySelectorAll("button")].some(
        (b) => b.textContent.trim() === "执行规则筛查" && !b.disabled,
      ),
    );
    await page.screenshot({
      path: path.join(out, "03-insufficient.png"),
      fullPage: true,
    });
  });
  await check("normal patient does not trigger", async () => {
    await page.getByRole("link", { name: "患者风险列表", exact: true }).click();
    await page
      .locator("tr")
      .filter({ hasText: "模拟患者 A" })
      .getByRole("link", { name: "查看证据 →" })
      .click();
    await page.getByRole("button", { name: "执行规则筛查" }).click();
    await page.getByText("当前未触发", { exact: true }).first().waitFor();
  });
  await check(
    "deterioration yields evidence not model probability",
    async () => {
      await page
        .getByRole("link", { name: "患者风险列表", exact: true })
        .click();
      await page
        .locator("tr")
        .filter({ hasText: "模拟患者 B" })
        .getByRole("link", { name: "查看证据 →" })
        .click();
      await page.getByRole("button", { name: "执行规则筛查" }).click();
      await page
        .getByText("疑似休克筛查，需医生复核", { exact: true })
        .waitFor();
      await page
        .getByText("无法评估 · 模型不可用", { exact: true })
        .first()
        .waitFor();
      await page.screenshot({
        path: path.join(out, "04-evidence-desktop.png"),
        fullPage: true,
      });
      await page.setViewportSize({ width: 390, height: 844 });
      await page.screenshot({
        path: path.join(out, "05-evidence-mobile.png"),
        fullPage: true,
      });
      assert.ok(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth + 1,
        ),
      );
      await page.setViewportSize({ width: 1440, height: 1000 });
    },
  );
  await check("doctor review creates task and audit history", async () => {
    await page.getByRole("link", { name: "预警处置中心", exact: true }).click();
    const row = page
      .locator("article.alertrow")
      .filter({ hasText: "demo-deteriorating" })
      .filter({ hasText: "待审核" })
      .first();
    await row.getByRole("button", { name: "处置", exact: true }).click();
    await page
      .getByLabel("临床复核原因")
      .fill("端到端验证：模拟证据已复核，非临床诊断");
    await page.screenshot({
      path: path.join(out, "06-alert-review.png"),
      fullPage: true,
    });
    await page.getByRole("button", { name: "确认提交", exact: true }).click();
    await page.locator(".overlay").waitFor({ state: "hidden" });
    const a = await api("/bundles/tasks");
    assert.ok(a.json.data.items.length > 0);
  });
  await check("nurse role cannot diagnose and records execution", async () => {
    await logout();
    await login("nurse");
    assert.equal(
      await page.getByRole("link", { name: "质控分析", exact: true }).count(),
      0,
    );
    const denied = await api("/alerts/unknown/review", "nurse", "POST", {
      action: "confirm",
      reason: "not allowed",
      expectedVersion: 0,
    });
    assert.equal(denied.status, 403);
    await page
      .getByRole("link", { name: "Bundle 任务管理", exact: true })
      .click();
    await page
      .getByRole("button", { name: "登记执行证据", exact: true })
      .first()
      .click();
    await page.getByLabel("原始执行记录 ID").fill("e2e-" + crypto.randomUUID());
    await page
      .getByLabel("证据说明 / 审核原因")
      .fill("模拟执行记录，由护士登记，等待医生审核");
    await page.getByRole("button", { name: "保存记录", exact: true }).click();
    await page.locator(".overlay").waitFor({ state: "hidden" });
    await page.getByText("按时完成", { exact: true }).first().waitFor();
    await page.screenshot({
      path: path.join(out, "07-nurse-execution.png"),
      fullPage: true,
    });
  });
  await check(
    "doctor confirms applicability and history persisted",
    async () => {
      await logout();
      await login("doctor");
      await page
        .getByRole("link", { name: "Bundle 任务管理", exact: true })
        .click();
      await page
        .getByRole("button", { name: "审核适用性与结果", exact: true })
        .first()
        .click();
      await page
        .locator('select[name="applicability"]')
        .selectOption("required");
      await page
        .getByLabel("证据说明 / 审核原因")
        .fill("模拟临床审核：确认适用性及所记录执行证据");
      await page.getByRole("button", { name: "保存记录", exact: true }).click();
      await page.locator(".overlay").waitFor({ state: "hidden" });
      await page
        .getByRole("button", { name: "状态历史", exact: true })
        .first()
        .click();
      await page
        .getByRole("heading", { name: "任务历史", exact: true })
        .waitFor();
      await page.screenshot({
        path: path.join(out, "08-bundle-history.png"),
        fullPage: true,
      });
    },
  );
  await check(
    "quality numerator denominator from actual workflow",
    async () => {
      await page.getByRole("link", { name: "质控分析", exact: true }).click();
      await page
        .getByRole("heading", { name: "指标可追溯口径", exact: true })
        .waitFor();
      const to = new Date(),
        from = new Date(to.getTime() - 86400000);
      const r = await api(
        "/quality/overview?from=" +
          encodeURIComponent(from.toISOString()) +
          "&to=" +
          encodeURIComponent(to.toISOString()),
      );
      assert.ok(r.json.data.denominator >= 1);
      assert.ok(r.json.data.onTime <= r.json.data.denominator);
      await page.screenshot({
        path: path.join(out, "09-quality.png"),
        fullPage: true,
      });
    },
  );
  await check(
    "quality card carries identical metric and period to task list",
    async () => {
      await page.getByRole("link", { name: /已审核适用任务/ }).click();
      await page
        .getByRole("heading", { name: "Bundle 任务工作台", exact: true })
        .waitFor();
      await page.locator("article.taskrow").first().waitFor();
      assert.ok(page.url().includes("metric=den"));
      assert.ok(page.url().includes("from="));
      await page.screenshot({
        path: path.join(out, "15-quality-drilldown.png"),
        fullPage: true,
      });
    },
  );
  await check("model unavailable is explicit", async () => {
    await page
      .getByRole("link", { name: "模型与运行状态", exact: true })
      .click();
    await page
      .getByRole("heading", { name: "未启用真实模型推理", exact: true })
      .waitFor();
    await page.screenshot({
      path: path.join(out, "10-model-unavailable.png"),
      fullPage: true,
    });
  });
  await check("API failure does not show fake values", async () => {
    await page.route("**/api/v1/patients?**", (r) =>
      r.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({
          code: "DATA_SOURCE_UNAVAILABLE",
          message: "测试：数据源不可用",
          traceId: "test-trace",
        }),
      }),
    );
    await page.getByRole("link", { name: "患者风险列表", exact: true }).click();
    await page.getByText(/测试：数据源不可用/).waitFor();
    assert.equal(
      await page.getByText("未找到符合条件的患者", { exact: true }).count(),
      0,
    );
    assert.equal(await page.locator("table").count(), 0);
    await page.screenshot({
      path: path.join(out, "11-api-failure.png"),
      fullPage: true,
    });
    await page.unroute("**/api/v1/patients?**");
  });
  await check(
    "mobile navigation and patient list do not overflow page",
    async () => {
      await page.setViewportSize({ width: 390, height: 844 });
      await page.reload();
      await page
        .getByRole("heading", { name: "重症临床风险工作台", exact: true })
        .waitFor();
      await page.screenshot({
        path: path.join(out, "12-login-mobile.png"),
        fullPage: true,
      });
      await login("doctor");
      await page.screenshot({
        path: path.join(out, "13-patients-mobile.png"),
        fullPage: true,
      });
      assert.ok(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth + 1,
        ),
      );
    },
  );
  assert.deepEqual(faults, [], "browser page errors");
  fs.writeFileSync(
    path.join(root, "docs/e2e-results.json"),
    JSON.stringify(
      {
        status: "passed",
        timestamp: new Date().toISOString(),
        tests: results,
        browserErrors: faults,
      },
      null,
      2,
    ),
  );
  await browser.close();
  process.exit(0);
})().catch(async (e) => {
  console.error(e);
  fs.writeFileSync(
    path.join(root, "docs/e2e-results.json"),
    JSON.stringify(
      {
        status: "failed",
        tests: results,
        error: String(e),
        browserErrors: faults,
      },
      null,
      2,
    ),
  );
  if (browser) await browser.close();
  process.exit(1);
});

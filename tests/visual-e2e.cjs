// Run after tests/e2e.cjs against a newly seeded, isolated *_demo database.
const { chromium } = require("playwright");
const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");
const root = path.resolve(__dirname, "..");
const env = Object.fromEntries(
  fs
    .readFileSync(path.join(root, ".runtime/demo.env"), "utf8")
    .trim()
    .split("\n")
    .map((x) => {
      const i = x.indexOf("=");
      return [x.slice(0, i), x.slice(i + 1)];
    }),
);
if (process.env.E2E_ALLOW_SIMULATION !== "true")
  throw Error("Requires isolated simulation");
const base = process.env.E2E_BASE_URL || "http://127.0.0.1:8080";
const out = path.join(root, "docs/screenshots");
const results = [],
  faults = [];
let browser;
const previews = {};
async function api(p) {
  const r = await fetch(base + "/api/v1" + p, {
    headers: {
      Authorization:
        "Basic " +
        Buffer.from("doctor:" + env.DOCTOR_PASSWORD).toString("base64"),
    },
  });
  assert.equal(r.status, 200);
  return (await r.json()).data;
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
    viewport: { width: 1440, height: 1120 },
    timezoneId: "Asia/Shanghai",
  });
  page.on("pageerror", (e) => faults.push(e.message));
  await page.goto(base + "/login");
  await page.getByLabel("角色账号").fill("doctor");
  await page.getByLabel("登录密码").fill(env.DOCTOR_PASSWORD);
  await page.getByRole("button", { name: "进入工作台" }).click();
  await page.getByText("模拟患者 B", { exact: true }).waitFor();
  async function openPatient(label) {
    await page.getByRole("link", { name: "患者风险列表", exact: true }).click();
    await page
      .locator("tr")
      .filter({ hasText: label })
      .getByRole("link", { name: "查看证据 →" })
      .click();
    await page
      .getByRole("heading", { name: "生命体征与检验趋势", exact: true })
      .waitFor();
  }
  async function snapshot(name) {
    previews[name] = await page.evaluate(() => ({
      body: document.querySelector("app-root").innerHTML,
      css: [...document.styleSheets]
        .map((s) => {
          try {
            return [...s.cssRules].map((r) => r.cssText).join("\n");
          } catch {
            return "";
          }
        })
        .join("\n"),
    }));
  }
  await check(
    "patient board uses server validated latest values, missing stays empty",
    async () => {
      const p = await api("/patients");
      const b = p.items.find((x) => x.id === "demo-deteriorating"),
        c = p.items.find((x) => x.id === "demo-insufficient");
      assert.equal(b.vitals.MAP.value, 58);
      assert.equal(b.vitals.MAP.unit, "mmHg");
      assert.deepEqual(c.vitals, {});
      await page.screenshot({
        path: path.join(out, "20-patients-v02.png"),
        fullPage: true,
      });
    },
  );
  await openPatient("模拟患者 B");
  let raw = await api("/patients/demo-deteriorating/clinical-data");
  await check(
    "six API-backed charts contain measured points with finite in-bounds geometry",
    async () => {
      await page.locator(".trend-tile svg").first().waitFor();
      assert.equal(await page.locator(".trend-tile svg").count(), 6);
      const circles = await page
        .locator(".trend-tile circle")
        .evaluateAll((cs) =>
          cs.map((c) => [
            Number(c.getAttribute("cx")),
            Number(c.getAttribute("cy")),
          ]),
        );
      assert.ok(circles.length > 100);
      assert.ok(
        circles.every(
          ([x, y]) =>
            Number.isFinite(x) &&
            Number.isFinite(y) &&
            x >= 36 &&
            x <= 310 &&
            y >= 19.99 &&
            y <= 116.01,
        ),
      );
      assert.ok(raw.items.length > 400);
      assert.ok(raw.items.every((o) => o.sourceSystem === "MOCK_ONLY"));
      await page.screenshot({
        path: path.join(out, "21-trends-desktop-v02.png"),
        fullPage: true,
      });
      await page
        .locator(".trend-section")
        .screenshot({ path: path.join(out, "22-trend-panel-v02.png") });
      await snapshot("患者趋势");
    },
  );
  await check(
    "6/12/24 hour selection changes actual plotted sample counts",
    async () => {
      const c12 = await page
        .locator(".trend-tile")
        .first()
        .locator("circle")
        .count();
      await page.getByRole("button", { name: "6 小时", exact: true }).click();
      const c6 = await page
        .locator(".trend-tile")
        .first()
        .locator("circle")
        .count();
      assert.ok(c6 < c12);
      await page.getByRole("button", { name: "24 小时", exact: true }).click();
      const c24 = await page
        .locator(".trend-tile")
        .first()
        .locator("circle")
        .count();
      assert.ok(c24 > c12);
      await page.getByRole("button", { name: "12 小时", exact: true }).click();
    },
  );
  await check(
    "wrong-unit record excluded, missing MAP samples create disconnected paths",
    async () => {
      const map = page.locator(".trend-tile").filter({ hasText: "平均动脉压" });
      assert.ok(
        raw.items.some((o) => o.parameterCode === "MAP" && o.unit === "kPa"),
      );
      assert.ok((await map.locator("path").count()) > 1);
      await map.getByText("1 条未绘制¹", { exact: false }).waitFor();
    },
  );
  await check(
    "clickable point reveals exact source record and times",
    async () => {
      const point = page
        .locator(".trend-tile")
        .first()
        .locator("circle")
        .last();
      await point.click();
      await page.locator(".point-evidence").waitFor();
      const latest = raw.items
        .filter((o) => o.parameterCode === "HR" && o.unit === "bpm")
        .sort((a, b) => Date.parse(b.eventTime) - Date.parse(a.eventTime))[0];
      await page
        .locator(".point-evidence")
        .getByText(latest.sourceRecordId, { exact: true })
        .waitFor();
      await page
        .locator(".point-evidence")
        .getByText("MOCK_ONLY", { exact: true })
        .waitFor();
      await page.getByRole("button", { name: "关闭数据点证据" }).click();
    },
  );
  await check(
    "raw evidence table is optional and retains invalid source record",
    async () => {
      const before = await page.locator(".workspace tbody tr").count();
      await page
        .getByRole("button", { name: "展开原始记录", exact: true })
        .click();
      assert.ok((await page.locator(".workspace tbody tr").count()) > before);
      await page
        .locator(".workspace")
        .getByText("kPa", { exact: false })
        .waitFor();
      await page.getByRole("button", { name: "仅看最新", exact: true }).click();
    },
  );
  await check(
    "missing patient has six honest empty panels and no fabricated curves",
    async () => {
      await openPatient("模拟患者 C");
      await page.locator(".chart-empty").first().waitFor();
      assert.equal(await page.locator(".chart-empty").count(), 6);
      assert.equal(await page.locator(".trend-tile svg").count(), 0);
      await page.screenshot({
        path: path.join(out, "23-missing-trends-v02.png"),
        fullPage: true,
      });
    },
  );
  await check(
    "overview renders observation table and actual queue",
    async () => {
      await page.getByRole("link", { name: "风险总览", exact: true }).click();
      await page.getByRole("heading", { name: "患者观察表" }).waitFor();
      await page.getByText("模拟患者 B", { exact: true }).waitFor();
      await page.getByRole("heading", { name: "待处理工作队列" }).waitFor();
      await page.screenshot({
        path: path.join(out, "24-overview-v02.png"),
        fullPage: true,
      });
      await snapshot("风险总览");
    },
  );
  await check(
    "quality bars reconcile with table and eligible denominator",
    async () => {
      await page.getByRole("link", { name: "质控分析", exact: true }).click();
      await page
        .getByRole("heading", { name: "统计明细", exact: true })
        .waitFor();
      const bars = await page.locator(".bar-row strong").allTextContents();
      assert.equal(bars.length, 5);
      const counts = bars.map(Number);
      const total = Number(
        await page.locator(".table-total td").nth(1).innerText(),
      );
      assert.equal(
        counts.reduce((a, b) => a + b, 0),
        total,
      );
      assert.equal(total, 1);
      assert.equal(counts[0], 1);
      await page.screenshot({
        path: path.join(out, "25-quality-v02.png"),
        fullPage: true,
      });
      await snapshot("质控分析");
    },
  );
  await check(
    "mobile charts and quality table stay within 390px viewport",
    async () => {
      await page.setViewportSize({ width: 390, height: 844 });
      await openPatient("模拟患者 B");
      await page.locator(".trend-tile svg").first().waitFor();
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        true,
      );
      await page.screenshot({
        path: path.join(out, "26-trends-mobile-v02.png"),
        fullPage: true,
      });
      await page.getByRole("link", { name: "质控分析", exact: true }).click();
      await page
        .getByRole("heading", { name: "统计明细", exact: true })
        .waitFor();
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        true,
      );
      await page.screenshot({
        path: path.join(out, "27-quality-mobile-v02.png"),
        fullPage: true,
      });
    },
  );
  await check("no browser runtime errors", async () =>
    assert.deepEqual(faults, []),
  );
  fs.writeFileSync(
    path.join(root, "docs/visual-e2e-results.json"),
    JSON.stringify(
      { status: "passed", tests: results, pageErrors: faults },
      null,
      2,
    ),
  );
  fs.writeFileSync(
    path.join(root, "docs/visual-snapshots.json"),
    JSON.stringify(previews),
  );
  await browser.close();
  process.exit(0);
})().catch(async (e) => {
  console.error(e);
  fs.writeFileSync(
    path.join(root, "docs/visual-e2e-results.json"),
    JSON.stringify(
      {
        status: "failed",
        tests: results,
        error: e.message,
        pageErrors: faults,
      },
      null,
      2,
    ),
  );
  if (browser) await browser.close();
  process.exit(1);
});

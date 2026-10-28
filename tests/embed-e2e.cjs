const { chromium } = require("playwright");
const fs = require("fs");
const path = require("path");
const assert = require("node:assert/strict");
const root = path.resolve(__dirname, ".."),
  out = path.join(root, "docs/screenshots");
if (process.env.E2E_ALLOW_SIMULATION !== "true")
  throw Error("isolated simulation only");
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
const base = process.env.E2E_BASE_URL || "http://127.0.0.1:8080",
  results = [],
  faults = [];
let browser;
async function check(name, fn) {
  await fn();
  results.push({ name, status: "passed" });
  console.log("PASS " + name);
}
(async () => {
  browser = await chromium.launch({
    headless: true,
    executablePath: process.env.CHROMIUM_PATH || chromium.executablePath(),
    args: ["--no-sandbox"],
  });
  const page = await browser.newPage({
    viewport: { width: 1680, height: 1080 },
    timezoneId: "Asia/Shanghai",
  });
  page.on("pageerror", (e) => faults.push(e.message));
  await page.addInitScript(() => {
    window.addEventListener("message", (e) => {
      if (e.data?.namespace === "icu.sepsis" && e.data.type === "READY")
        window.__embedChannel = e.data.channel;
    });
  });
  let grant = null;
  page.on("response", async (r) => {
    if (r.url().endsWith("/embed-api/exchange") && r.status() === 200) {
      grant = (await r.json()).data;
    }
  });
  await page.goto(base + "/host-demo.html");
  await page.getByLabel("角色账号").fill("doctor");
  await page.getByLabel("演示密码").fill(env.DOCTOR_PASSWORD);
  await page.getByRole("button", { name: "打开宿主工作台" }).click();
  const frame = page.frameLocator("#workbench");
  async function chosen(label) {
    await frame
      .locator(".embed-context")
      .getByText(label, { exact: true })
      .waitFor();
    await page.waitForFunction(
      () => !document.querySelector(".patient.pending"),
    );
  }
  async function choose(label) {
    await page.getByRole("button", { name: new RegExp("床 " + label) }).click();
    await chosen(label);
  }
  await check(
    "actual cross-origin iframe handshake loads only selected patient",
    async () => {
      await chosen("模拟患者 B");
      assert.notEqual(
        new URL(page.url()).origin,
        new URL(await page.locator("#workbench").getAttribute("src")).origin,
      );
      await frame.locator(".trend-tile svg").first().waitFor();
      assert.equal(await frame.locator(".trend-tile svg").count(), 6);
    },
  );
  await check(
    "embedded page has no second sidebar, global topbar or patient picker",
    async () => {
      assert.equal(
        await frame.locator("aside,.topbar,.modulebar,.back").count(),
        0,
      );
      assert.equal(
        await frame
          .getByRole("heading", { name: "患者风险列表", exact: true })
          .count(),
        0,
      );
      assert.equal(await frame.locator(".embed-tabs button").count(), 4);
      await page.screenshot({
        path: path.join(out, "30-host-embedded-desktop.png"),
        fullPage: true,
      });
    },
  );
  await check(
    "bearer grant is restricted to current patient and encounter",
    async () => {
      assert.ok(grant?.accessToken);
      const headers = { Authorization: "Bearer " + grant.accessToken };
      const other = await fetch(base + "/api/v1/patients/demo-normal", {
        headers,
      });
      assert.equal(other.status, 403);
      const scoped = await (
        await fetch(base + "/api/v1/patients", { headers })
      ).json();
      assert.equal(scoped.data.total, 1);
      assert.equal(scoped.data.items[0].id, "demo-deteriorating");
    },
  );
  await check(
    "changing patient replaces values rather than retaining previous curves",
    async () => {
      await choose("模拟患者 A");
      await frame
        .locator(".trend-tile")
        .first()
        .getByText("80", { exact: false })
        .first()
        .waitFor();
      const reading = await frame.locator(".trend-reading").first().innerText();
      assert.match(reading, /80/);
      await choose("模拟患者 C");
      await frame.locator(".chart-empty").first().waitFor();
      assert.equal(await frame.locator(".trend-tile svg").count(), 0);
      assert.equal(await frame.locator(".point-evidence").count(), 0);
      await page.screenshot({
        path: path.join(out, "31-host-missing-patient.png"),
        fullPage: true,
      });
    },
  );
  await check(
    "late response for previous patient cannot overwrite a new context",
    async () => {
      let release;
      const gate = new Promise((r) => (release = r));
      let seenResolve;
      const seen = new Promise((r) => (seenResolve = r));
      await page.route(
        "**/api/v1/patients/demo-normal/clinical-data",
        async (route) => {
          const response = await route.fetch();
          seenResolve();
          await gate;
          await route.fulfill({ response });
        },
      );
      await choose("模拟患者 A");
      await seen;
      await choose("模拟患者 B");
      await frame.locator(".trend-tile svg").first().waitFor();
      release();
      await page.waitForTimeout(350);
      await page.unroute("**/api/v1/patients/demo-normal/clinical-data");
      await frame
        .locator(".embed-context")
        .getByText("模拟患者 B", { exact: true })
        .waitFor();
      assert.match(
        await frame.locator(".trend-reading").first().innerText(),
        /120/,
      );
    },
  );
  await check(
    "source, origin and channel spoofed messages cannot change active context",
    async () => {
      const channel = await page.evaluate(() => window.__embedChannel);
      const m = {
        namespace: "icu.sepsis",
        version: 1,
        type: "CLEAR_CONTEXT",
        channel,
        requestId: "spoof-request-001",
      };
      const child = page
        .frames()
        .find((f) => new URL(f.url() || base).pathname === "/embed");
      await child.evaluate((m) => {
        window.dispatchEvent(
          new MessageEvent("message", {
            origin: "https://evil.example.test",
            source: window.parent,
            data: m,
          }),
        );
        window.dispatchEvent(
          new MessageEvent("message", {
            origin: location.origin,
            source: window,
            data: m,
          }),
        );
      }, m);
      await page.evaluate((m) => {
        const f = document.querySelector("#workbench");
        f.contentWindow.postMessage(
          { ...m, channel: "wrong-channel" },
          new URL(f.src).origin,
        );
      }, m);
      await page.waitForTimeout(150);
      await frame
        .locator(".embed-context")
        .getByText("模拟患者 B", { exact: true })
        .waitFor();
    },
  );
  await check(
    "screening and evidence work without routing out of iframe mode",
    async () => {
      await frame
        .getByRole("button", { name: "执行规则筛查", exact: true })
        .click();
      await frame
        .getByText("血流动力学异常", { exact: true })
        .first()
        .waitFor();
      await frame
        .getByRole("button", { name: "执行规则筛查", exact: true })
        .waitFor();
      await frame
        .getByRole("button", { name: "临床证据", exact: true })
        .click();
      await frame.getByRole("heading", { name: /临床证据/ }).waitFor();
      assert.equal(await frame.locator(".trend-tile").count(), 0);
      assert.equal(
        new URL(
          page
            .frames()
            .find((f) => f.url().includes("/embed"))
            .url(),
        ).pathname,
        "/embed",
      );
      await page.screenshot({
        path: path.join(out, "32-host-evidence.png"),
        fullPage: true,
      });
    },
  );
  await check(
    "unsaved review rejects patient change and host selection stays consistent",
    async () => {
      await frame
        .getByRole("button", { name: "预警复核", exact: true })
        .click();
      await frame
        .getByRole("heading", { name: "预警处置中心", exact: true })
        .waitFor();
      await frame
        .getByRole("button", { name: "处置", exact: true })
        .first()
        .click();
      await frame.getByLabel("临床复核原因").fill("未保存的模拟复核草稿");
      await page.getByRole("button", { name: /床 模拟患者 A/ }).click();
      await page
        .locator("#status")
        .getByText("当前操作尚未保存或正在处理中", { exact: false })
        .waitFor();
      assert.equal(
        await page.locator(".patient.active").getAttribute("data-patient-id"),
        "demo-deteriorating",
      );
      await frame
        .locator(".embed-context")
        .getByText("模拟患者 B", { exact: true })
        .waitFor();
      await frame.getByRole("button", { name: "取消", exact: true }).click();
    },
  );
  await check(
    "contextual evidence link remains in embedded workspace",
    async () => {
      await frame.getByText("核查患者证据", { exact: true }).first().click();
      await frame.getByRole("heading", { name: /临床证据/ }).waitFor();
      assert.equal(await frame.locator("aside").count(), 0);
    },
  );
  await check(
    "scoped task tab shows current-patient workflow only",
    async () => {
      await frame
        .getByRole("button", { name: "复评任务", exact: true })
        .click();
      await frame
        .getByRole("heading", { name: "Bundle 任务工作台", exact: true })
        .waitFor();
      const data = await frame.locator(".tasktitle").allTextContents();
      assert.ok(data.every((x) => x.includes("demo-deteriorating")));
    },
  );
  await check(
    "explicit clear removes patient data and reload renegotiates a new channel",
    async () => {
      const previous = await page.evaluate(() => window.__embedChannel);
      await page.evaluate(() => {
        const f = document.querySelector("#workbench");
        f.contentWindow.postMessage(
          {
            namespace: "icu.sepsis",
            version: 1,
            type: "CLEAR_CONTEXT",
            channel: window.__embedChannel,
            requestId: "clear-context-001",
          },
          new URL(f.src).origin,
        );
      });
      await frame
        .getByRole("heading", { name: "等待宿主患者上下文", exact: true })
        .waitFor();
      assert.equal(await frame.locator(".trend-tile").count(), 0);
      const child = page.frames().find((f) => f.url().includes("/embed"));
      await child.goto(child.url());
      await chosen("模拟患者 B");
      assert.notEqual(
        await page.evaluate(() => window.__embedChannel),
        previous,
      );
      await frame.locator(".trend-tile svg").first().waitFor();
    },
  );
  await check(
    "narrow host retains one patient picker with no horizontal page overflow",
    async () => {
      await page.setViewportSize({ width: 650, height: 1000 });
      await page.waitForTimeout(350);
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        true,
      );
      const child = page.frames().find((f) => f.url().includes("/embed"));
      assert.equal(
        await child.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        true,
      );
      await page.screenshot({
        path: path.join(out, "33-host-embedded-narrow.png"),
        fullPage: true,
      });
    },
  );
  await check("embedded browser has no runtime exceptions", async () =>
    assert.deepEqual(faults, []),
  );
  fs.writeFileSync(
    path.join(root, "docs/embed-e2e-results.json"),
    JSON.stringify(
      { status: "passed", tests: results, pageErrors: faults },
      null,
      2,
    ),
  );
  await browser.close();
  process.exit(0);
})().catch(async (e) => {
  console.error(e);
  fs.writeFileSync(
    path.join(root, "docs/embed-e2e-results.json"),
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

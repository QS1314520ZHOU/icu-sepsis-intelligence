# iframe / postMessage 宿主接入契约（0.3）

## 分工与入口
宿主保留唯一的登录、全局菜单、患者列表与模块页签。iframe 不再嵌套 ICU 顶栏、侧边菜单、患者搜索或独立登录表单；仅显示当前患者的核对条和“风险趋势 / 临床证据 / 预警复核 / 复评任务”局部页签。

- 实际子应用入口：`/embed?parentOrigin=<URL编码后的宿主origin>`。
- 本机完整宿主模拟：`http://127.0.0.1:8080/host-demo.html`。该页面自身提供唯一患者列表，并通过 localhost ↔ 127.0.0.1 的真实不同来源 iframe 验证握手；不是医院宿主。
- `/patients` 等独立模式继续保留，仅用于开发和独立验收，不作为正式宿主内的第二层导航。
- 科室总览与质控统计应由宿主的独立全局入口打开，不放进单患者子应用导航。

## 两套并行协议

### A. 真实宿主 SmartCare 协议（与科室概览 / 质控看板一致）

依据现场《科室概览 postMessage 对接文档 v3》：

| 方向 | type | 含义 |
|------|------|------|
| iframe → 宿主 | `SmartCareReady` | 我已就绪，请下发当前上下文 |
| 宿主 → iframe | `SmartCare` | 下发 `account` / `patient` / `token` |
| iframe → 宿主 | `select-patient` | 选中患者（`patientId` = SmartCare `patient._id` 十六进制） |
| iframe → 宿主 | `SmartCareNavigate` | 跳转模块页签，默认 `doctor-diagnosis-patient-review` |

`SmartCare.patient` 关键字段按 v3 文档读取：`id`（选人）、`deptCode || deptCode2`、`showBed || hisBed`、`mrn`、`hisPid`。

子应用收到 `SmartCare` 后调用 `POST /embed-api/host-context`，用 `patientId` 从只读 SmartCare 库物化业务患者并签发 10 分钟患者范围 Bearer。业务写入仍只进 `icu_sepsis_*` 库，不写 SmartCare/DataCenter。

**不需要医院 SSO。** 正式接入只要 iframe + 上表四条消息；`SmartCare.token/account` 可忽略。演示票据协议 B 仅用于 host-demo/测试，不是生产 SSO。

### B. 演示安全票据协议（host-demo / 集成测试）

仍使用 `READY` / `SET_CONTEXT` / `CONTEXT_APPLIED` 与 45 秒一次性 code，见下文。

## 部署配置

### 纯 iframe（推荐现场）
```bash
CLINICAL_SOURCE_MODE=smartcare
SMARTCARE_MONGO_URI=mongodb://192.168.31.185:27017/SmartCare
DATACENTER_MONGO_URI=mongodb://192.168.31.185:27017/DataCenter
# 医院库若免密可留空；有账号再填（或直接写进 URI：mongodb://user:pass@host:27017/SmartCare?authSource=SmartCare）
SMARTCARE_MONGO_USERNAME=
SMARTCARE_MONGO_PASSWORD=
SMARTCARE_MONGO_AUTH_SOURCE=
DATACENTER_MONGO_USERNAME=
DATACENTER_MONGO_PASSWORD=
DATACENTER_MONGO_AUTH_SOURCE=
MONGODB_URI=mongodb://127.0.0.1:27027/icu_sepsis_demo?replicaSet=rs0
EMBED_PARENT_ORIGINS=http://192.168.31.167:60000
EMBED_ALLOW_INSECURE_LAN_ORIGINS=true
# 本地 Basic 启动用，不是医院 SSO
DOCTOR_PASSWORD=至少十六位随机密码
NURSE_PASSWORD=至少十六位随机密码
QUALITY_PASSWORD=至少十六位随机密码
```

模板：`scripts/iframe.env.example`。

现场实测 `192.168.31.185:27017` 可免密列出 `SmartCare` / `DataCenter`；若医院后续开了鉴权，只填上面 USERNAME/PASSWORD 即可，无需改代码。

### 演示票据（可选）
```bash
EMBED_PARENT_ORIGINS=https://icu.example.test
```

示例域名必须替换为实际已批准的宿主 origin，**仅协议 + 主机 + 端口，不带路径、尾斜线或通配符**。生产只接受 HTTPS；演示可使用明确的 loopback HTTP origin。默认演示白名单为 `http://127.0.0.1:8080,http://localhost:8080`，生产默认不提供额外宿主白名单。

`parentOrigin` 查询参数仅用于选择握手目标，不是授权依据；演示票据协议仍须匹配服务端白名单和 `event.origin`。SmartCare 协议在医院内网联调阶段不强制 HTTPS origin 校验，正式上线应收紧。

后端响应使用 `Content-Security-Policy: frame-ancestors 'self' <已批准宿主origin>`，不再同时发送冲突的 `X-Frame-Options: DENY`。真实反向代理不得再叠加 DENY/SAMEORIGIN 阻断合法跨来源嵌入；也不能直接放宽为任意来源。宿主自身 CSP 的 `frame-src` 应允许子应用 origin。若 iframe 加 sandbox，需评估 scripts、origin 和认证策略，不能随意复制 sandbox 组合。

## 一次握手
1. 宿主监听 message，先验证 `event.source === iframe.contentWindow` 和精确子应用 origin，再加载 iframe。
2. 子应用读取公开的 `/embed-api/config`，向已批准的 parentOrigin 发送 `READY`，携带每次加载随机生成的 `channel`。
3. 宿主在后端完成认证和患者授权核验后，为选定 patientId + encounterId 获取一次性 code。
4. 宿主发送 `SET_CONTEXT`，目标必须为精确子应用 origin，不能用 `*`。
5. 子应用验证来源、source、namespace、version、channel、requestId 与字段结构；清除旧患者组件及令牌，再与自身后端交换票据。后端返回的患者/住院/科室必须与请求一致。
6. 子应用发出 `CONTEXT_APPLIED` 后，宿主才提交其患者选中态。切换中宿主应显示待确认状态，不能让宿主和子应用暂时展示两个不同患者。

## 消息示例
以下是本演示实现的明确协议，不声称是现有医院宿主已经采用的格式。接入真实宿主时可编写适配层映射已有 type/字段，但不能删除来源和后端授权核验。

```ts
// 子应用 → 宿主；channel 每次 iframe 加载重新生成
{
  namespace: 'icu.sepsis', version: 1, type: 'READY',
  channel: '<随机通道>',
  payload: { capabilities: ['patient-context', 'scoped-demo-ticket', 'context-ack'] }
}

// 宿主 → 子应用
{
  namespace: 'icu.sepsis', version: 1, type: 'SET_CONTEXT',
  channel: '<READY中收到的通道>', requestId: '<唯一请求ID>',
  payload: {
    code: '<后端签发的一次性票据>',
    patientId: '<子应用可识别的患者关联ID>',
    encounterId: '<本次住院关联ID>'
  }
}

// 成功确认：宿主仅在 requestId 与患者/住院匹配时接受
{
  namespace: 'icu.sepsis', version: 1, type: 'CONTEXT_APPLIED',
  channel: '<通道>', requestId: '<原请求ID>',
  payload: { patientId: '<已核验患者ID>', encounterId: '<已核验住院ID>' }
}

// 主动清空
{ namespace: 'icu.sepsis', version: 1, type: 'CLEAR_CONTEXT', channel, requestId }
```

其余响应：`CONTEXT_CLEARED`、`CONTEXT_REJECTED`（`UNSAVED_OR_BUSY` / `AUTH_OR_CONTEXT_INVALID`）、`AUTH_REQUIRED`（过期）。消息不携带密码、长期 bearer、角色、姓名、诊断或曲线数据。

## 演示认证（不是生产 SSO）
- `POST /api/v1/embed-codes` 要求已认证的宿主开发账号；输入 parentOrigin、channel、patientId、encounterId。服务端核对科室/患者/住院后，签发45秒单次 code。
- `POST /embed-api/exchange` 只接受 code、parentOrigin、channel；一次性消费后返回10分钟 opaque bearer。角色和患者范围来自后端签发记录，不接受前端自报权限。
- bearer 只保存在子应用内存，所有查询和实体读取均限制到该患者与该次住院。切换同通道会使旧 token 失效；清空/销毁尝试调用 revoke，过期或无效 token 返回401。
- `POST /embed-api/revoke` 主动撤销当前 bearer；不会因为相同 channel 撤销新上下文的独立 grant。
- **发行器仅在 DEMO_MODE=true 时启用。生产模式不能以它替代医院 SSO，会拒绝签发。** 真实接入必须另行实现宿主身份可信交换、患者动态授权与标识映射；当前内存发行/令牌存储也不支持多实例共享或生产级限速审计。
- 本机 host-demo 的 Basic 凭据仅保存在宿主演示页面内存并只用于本机认证请求，绝不通过 postMessage 传给子应用。密码不在交付包或截图内。

## 切换患者与临床操作保护
- 卸载旧患者视图，立即清除曲线、来源弹层、证据表、筛查、预警、任务和旧身份令牌，不在加载期间继续展示上一患者。
- 使用 generation 防止较慢的旧请求覆盖新上下文。关联到错误住院的票据拒绝加载。
- 子应用有未保存预警/任务草稿或正在写入时，返回 `UNSAVED_OR_BUSY`，宿主保持原选中态；不能静默丢弃后把操作记到新患者。
- 宿主演示12秒切换超时后发送明确清空并允许重试；iframe重载重新握手，不复用旧 channel。
- token过期清空患者区并请求宿主重新授权。不能把空白、来源故障或过期显示成“当前正常”。

## 正式联调仍需现场提供
实际宿主/子应用 origin 与反向代理路径、现有消息约定、可信 SSO 的签发/兑换方式、patientId 与 encounterId 的权威映射、患者授权策略、现场 Java/Angular/Mongo 版本与正式规则审批。这里没有读取或连接真实医院患者库，也未对宿主截图中的真实患者进行复刻或导入。

当前交付默认根路径部署。若挂在 /sepsis/ 等非根路径，需一并配置 Angular base href、路由匹配、资源/API前缀及代理重写，不能只修改iframe的src。

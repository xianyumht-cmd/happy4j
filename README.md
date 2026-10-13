# Happy4J · 欢乐斗地主 Java 客户端

本项目的核心是两部分逆向工程成果：完整脱机恢复的**欢乐斗地主（HLDDZ）网络协议**
（QQ/微信扫码、TSDK SafeLogin、Lotus TCP 线格式、52 个 `.proto` / 1,350 个消息），
以及基于 **DouZero** ResNet 模型、在浏览器本地推理的**全自动打牌 AI**。协议细节见
[`docs/hlddz-protocol.md`](docs/hlddz-protocol.md)，AI 部分见
[`docs/ai-douzero.md`](docs/ai-douzero.md)。

当前版本采用 MySQL + Flyway、BCrypt 注册登录、多用户会话隔离和原生 WebSocket 命令总线。
前端采用 Vue 3 Composition API + Vite 8，扫码轮询在服务端执行，浏览器只接收
`EVENT/SNAPSHOT` 推送。架构说明见 [`docs/architecture.md`](docs/architecture.md)。

场次、功能参数、任务定义和加密游戏登录状态均落在 MySQL；任务状态按照 JS 客户端的
MissionSvr `7304` 明确 ID 列表查询，并与 CGI `32791` 在线详情合并。旧的开发 CLI、示例
payload 及 1.4MB JSON manifest 已从运行工程移除。

这是从 `apk/hlddz.apk` 恢复的可视化 Java 客户端，不是 CLI，也不是本地规则模拟器。
浏览器只负责界面；扫码授权、SafeLogin、Lotus TCP、活动、奖励、快速入场、坐下、准备和出牌请求都由 Java 直接连接原服务完成。

## 运行

```bash
docker compose up -d mysql
./gradlew clean test bootJar --no-daemon
java -jar build/libs/happy4j-0.0.1-SNAPSHOT.jar
```

打开 <http://127.0.0.1:8080/>，选择“QQ 扫码”或“微信扫码”并用手机确认。
端口可通过 `HAPPY4J_PORT` 修改。

前端独立开发：

```bash
cd frontend
npm ci
npm run dev
```

Vite 将 `/api` 和 `/ws` 代理到 `8080`。Gradle `processResources/bootJar` 会自动执行
Prettier、ESLint、Vitest 和 Vite 生产构建，不再使用手写 DOM 渲染或打包前静态页面。

### 微信扫码

默认直接复用 APK 内的 MSDK v5 流程：Java 调用 `/v2/auth/scan_login` 取得
`random/timestamp/sig`，再执行微信 DiffDev `qrconnect`；确认后把 `code` 交给
`/v2/auth/login` 换取 `openid/token`。因此开箱即用，无需设置 AppSecret 或 sdk_ticket。

若要切换到自有微信开放平台参数，仍可设置以下变量覆盖默认流程：

```bash
# 直接提供有效的 SDK ticket
export HAPPY4J_WECHAT_SDK_TICKET='TICKET'
export HAPPY4J_WECHAT_APP_SECRET='APP_SECRET'   # code 换 token

# 或只提供 AppSecret，由 Java 获取 access_token 和 type=2 sdk_ticket
export HAPPY4J_WECHAT_APP_SECRET='APP_SECRET'
```

Java 实现与 APK 中 `com.tencent.mm.opensdk.diffdev` 一致：`MSDK scan_login → qrconnect → long qrconnect(408/404/405) → code → MSDK login`。

## 使用流程

1. Java 可创建 QQ Connect xlogin 会话，拉取真实 `ptqrshow` PNG，并用 `ptqrtoken` 轮询 `ptqrlogin`。
2. QQ m_authorize 携带 APK 包名、证书 MD5、秒级时间戳及 OpenSDK 动态 `sign`；轮询继续携带 `pt_openlogin_data/type/device/ptopt`，确认后解析 `access_token/openid/pay_token/key`，再通过 QQ channel 2 换成 MSDK v5 凭证。
3. QQ/微信 MSDK 换票都携带 `QRCode=true`、`login_type=MSDK_SCAN_LOGIN`、四段数字 `seq` 及原生 `MSDKUtils.GetDeviceInfoWrapper` 设备信封；服务响应的 `seq` 必须与请求完全相同，国内 TSDK 随后调用 provider SafeLogin。
4. 解密响应的 `skey/enckey`：`skey` 作为 CCSHead 的 ST，`enckey` 的前 16 字节作为 Lotus QQ-TEA 密钥；random key 只负责解密 SafeLogin 响应。
5. 大厅连接后通过 `DDZ3DQueryInfo.DDZ3DQueryInfoService/QueryInfoByBitflag` 实时刷新欢乐豆/钻石余额；通过 wxpkg 同款 `15502 CGI-over-Lotus` 自动拉取 QQ/微信昵称头像、任务状态、每日欢乐豆补助次数，并支持签到、任务、宝箱和补助领取后自动刷新余额/任务。
6. 场次直接读取 APK 的 `michallExtranet.xml`；选择场次先用大厅 Lotus 执行 TDR `6100`，再通过 AppDir `10401` 动态下载 FreeIP/普通 `GameSvrList.ini`，按返回的 `svrId`（缺项时与原生一致回退 `30000`）建立独立 GameSvr TCP 通道。
7. GameSvr 通道保留 QuickGame 返回的路由 ID，先发送原生 TGW Host 握手与 BookRouter `7601`，并补齐 capability/device/IP-port 登录 TLV；`15814 CNotifyCallPlayer` 会还原成其内嵌响应。随后以 TDR 执行 `15801 → 15803`；微信小游戏普通快速匹配严格使用 `SitDownFlag=3 / RoomID / TableID=-1 / SeatID=-1 / TLVNum=0`。登录成功后的 CCS outer sequence 自动切换为服务器返回的 `PlayerID`。
8. `15815 / PZa(uJa)` 驱动发牌、叫抢、底牌、加倍、首出、出牌与结算状态机；前端只渲染服务端开放的按钮。
9. 叫/抢地主、加倍和出牌按 JS `g.vK → g.nra` 的 `Pm/uJa` TDR union 编码，并经
   `15813 / CRequestPlayHGPara` 发送；准备下一局使用 `15810 / CRequestReadyHGPara`。

`15814` 的首个 i16 是批量消息数，不是固定的通知类型；坐下成功时一次封套可包含
`15803/15810/15809/15815` 等 15 条消息。客户端逐条拆包。若服务器返回 `11007`，
则按“账号已在对局中”进入恢复状态，不再当作新匹配失败。

QuickGame 临时返回 `-4` 时，页面会把该场次冷却 30 秒，避免连续重试旧 XML 中的静态开关，同时允许动态场次恢复后再次进入。GameSvr 返回 `10621/10622` 时，页面按 APK 的原始流程展示服务器下发的验证入口；完成后可一键重新进入刚才的场次。`11306` 会明确标注为坐下匹配阶段返回并保留重试/换场入口。

## 架构

```text
web/                    注册、登录、管理与兼容 REST
websocket/              鉴权命令总线、服务端扫码轮询、牌局状态推送
frontend/               Vue 3 + Vite 大厅、专用房间、拖动选牌、记牌器与结算 UI
application/            用例编排、状态机、消息目录
application/port/       QR、SafeLogin、模式目录、传输端口
 domain/                会话、凭证、场次、活动、牌桌聚合
infrastructure/auth/    QQ/微信 QR + 国内/海外可切换的 TSDK SafeLogin
infrastructure/config/  APK XML 场次目录
infrastructure/transport/ 大厅 Lotus、AppDir、独立 GameSvr TCP 与请求相关性
protocol/wire/          CCS/CMsg、QQ-TEA、压缩、TDR
protocol/schema/        1,350 个消息的动态 Descriptor
protocol/generated/     Gradle 构建生成的强类型 Java
```

依赖方向是 `web → application → domain/port ← infrastructure`。新增登录渠道、活动或游戏模式时实现端口或增加声明式 `ProtocolCallCatalog` 条目，无需修改控制器与 TCP 编解码器。

## 已恢复协议

- 52 个 `.proto`、1,350 个 protobuf 消息、253 个枚举
- `Native.HuanLeGameSvr.*` 241 个核心游戏消息
- CCSHead/ST、CMsgHead/CUnknowMsgPara 长度封套、Lotus enckey QQ-TEA、zlib、LZ4 block、TCP 分片/粘包
- TDR 大端基础类型、varint、定长 C 字符串
- QQ QR OAuth、微信 MSDK v5、TSDK legacy/GCloud SafeLogin、Lotus 会话及 gRPC-over-Lotus
- 8 个启用场次、签到/宝箱/欢乐豆优惠/赛事请求、TDR 快速入场、AppDir 路由和独立 GameSvr 牌桌操作

逆向地址、字段和线格式见 [`docs/hlddz-protocol.md`](docs/hlddz-protocol.md)。运行产物只保留在线客户端所需的 descriptor 与协议实现。

- 完整的 JS 牌局/换桌/再来一局对照见 `docs/wxpkg-round-flow.md`。

## AI 全自动出牌（DouZero）

默认推理引擎是 **DouZero FullAuto ResNet**：叫抢地主、加倍、出牌三个阶段各自加载
独立的 ONNX 模型，全部在浏览器内用 `onnxruntime-web` 本地推理，手牌不会上传到任何
服务端或第三方。模型与转换脚本来自两个上游开源项目（均为 Apache-2.0，见
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)）：

- [`DouZero_For_HappyDouDiZhu`](https://github.com/tianqiraf/DouZero_For_HappyDouDiZhu) —
  早期 WP 模型家族，训练/导出脚本保留在 `tools/douzero_happy/`。
- [`DouZero_For_HLDDZ_FullAuto`](https://github.com/Vincentzyx/DouZero_For_HLDDZ_FullAuto) —
  当前生产使用的三角色 ResNet ADP 模型（`frontend/public/models/douzero_fullauto/`），
  相比旧 WP 模型使用完整 `40×54` 局面矩阵、独立的叫抢/加倍评分网络，并在模型评分之外叠加
  主动压制、连续控牌等启发式，避免长期保守不出牌。

决策链：进入选定场次 → 叫/抢地主（`bid.onnx` 打分 + 阈值）→ 加倍（局前评分模型 + 超级加倍道具
核对）→ 出牌（枚举合法动作、批量取 ADP 评分，按立即出完/控牌路径/低分炸弹/模型最高分依次决策）。
完整实现细节、特征工程和状态完整性校验见 [`docs/ai-douzero.md`](docs/ai-douzero.md)。

除本地 DouZero 模型外，也可以在 AI 面板里切换为 **OpenAI 兼容大模型**决策
（`OpenAiDecisionService`）：把当前候选动作和公开牌局信息发给任意 OpenAI 协议兼容的
Chat Completions 接口，由大模型返回一个 `choice`；调用失败时自动回退到本地 DouZero 模型。

### AI 稳定性修复（开发分支）

OpenRouter 可继续使用 `openai/gpt-6-luna`，无须切换模型。
`HAPPY4J_AI_MAX_OUTPUT_TOKENS` 可调整兼容接口输出上限（默认 1024）。
对于缺失权威结算手牌的对局，统计显示“待确认”而非通过欢乐豆变化猜测胜负。
仅在确认胜负时计入胜率；老版本基于欢乐豆的统计不会混入新统计。

本次分支的修改范围、已知局限、回归测试和 Windows 10 免 Docker 升级步骤见
[`docs/ai-reliability.md`](docs/ai-reliability.md)。本分支不自动发布或覆盖现有安装。

## 并发容量与代码质量

单实例默认最多保持 **64 个不同用户的活跃 Lotus 登录**，每个站点用户默认 1 个游戏连接。
容量、工作线程和扫码轮询线程均保存在 `server_config`：

- `runtime.max-active-users=64`
- `runtime.max-active-sessions-per-user=1`
- `runtime.worker-threads=32`
- `runtime.worker-queue-capacity=512`
- `runtime.qr-poll-threads=8`

一个大厅用户占用 1 个 Lotus reader；进入牌桌后增加 GameSvr reader 和 heartbeat，默认容量下
约为 192 个长驻协议线程。提升上限前应同步增加 JVM 内存、文件描述符、Tomcat 线程和 MySQL
连接池，并执行真实账号压测。

Java 和测试使用 JetBrains formatter；Vue/JavaScript/CSS 由 Prettier 和 ESLint 校验；
`.editorconfig` 固定后续风格。Gradle 对主代码和测试启用
`-Xlint:all,-processing -Werror`，并在每次测试时执行前端 format/lint/unit test。

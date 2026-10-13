# Happy4J — End-to-End Readiness Review (2026-10-13)

**审查仓库** [xianyumht-cmd/happy4j](https://github.com/xianyumht-cmd/happy4j)  
**原始基线** `main` @ `37be0f1a39da66eedad8026dfa1dad8ca8caa424`  
**审查模式** 只读源码 / 测试 / 构建历史 / 既往用户日志。无代码修复、无合并、无发布。
**专业审查提示词** [prompt-en.md](./prompt-en.md)

## 核心结论

**不应当将当前版本判定为“已通过无人值守实战验收”。** 项目已实现大量逆向协议、完整 DouZero 网络和自动对局流程，也有 Java 和前端测试；主要瓶颈是扫码认证、大厅连接、前端当前会话、自动操作和故障恢复之间缺少可靠的端到端生命周期管理。此前一个大厅修复分支虽然 CI 成功，但用户实际反馈出现扫码识别回退，证明不能把 CI 通过等同于实战可用。

## 优先问题清单

| ID | 等级 | 发现及用户影响 | 源码依据 | 验证 |
|---|---|---|---|---|
| F01 | **P0** | QR_STATUS=CONFIRMED 时立即显示“游戏账号登录成功”，但 SafeLogin / Lotus 大厅仍在异步执行，可能误报登录成功 | [前端](https://github.com/xianyumht-cmd/happy4j/blob/37be0f1a39da66eedad8026dfa1dad8ca8caa424/frontend/src/composables/use-game-client.js#L103-L109) / [后端](https://github.com/xianyumht-cmd/happy4j/blob/37be0f1a39da66eedad8026dfa1dad8ca8caa424/src/main/java/cc/superbaby/happy4j/application/HappyDdzClientService.java#L161-L184) | 源码明确 |
| F02 | **P1** | 旧 Lotus 断线后连接对象留在注册表，容量计算不区分死活；新扫码可能被旧会话占位 | [注册表](https://github.com/xianyumht-cmd/happy4j/blob/37be0f1a39da66eedad8026dfa1dad8ca8caa424/src/main/java/cc/superbaby/happy4j/application/ClientRuntimeRegistry.java#L45-L85) / [断线事件](https://github.com/xianyumht-cmd/happy4j/blob/37be0f1a39da66eedad8026dfa1dad8ca8caa424/src/main/java/cc/superbaby/happy4j/infrastructure/transport/SocketLotusTransport.java#L326-L341) | 源码明确；历史日志相符，实际阻断异常未捕获 |
| F03 | **P1** | `activeSessionId ||=` 保留旧活动会话，新登录的有效 session 可能未成为当前账号 | [选择](https://github.com/xianyumht-cmd/happy4j/blob/37be0f1a39da66eedad8026dfa1dad8ca8caa424/frontend/src/composables/use-game-client.js#L30-L43) / [BOOT](https://github.com/xianyumht-cmd/happy4j/blob/37be0f1a39da66eedad8026dfa1dad8ca8caa424/frontend/src/composables/use-game-client.js#L81-L101) | 源码明确 |
| F04 | **P1** | 自动打牌只以网页 WebSocket + 保存的 UIN 作为主要运行前置条件，没有确认真实 Lotus 大厅健康 | [自动托管](https://github.com/xianyumht-cmd/happy4j/blob/37be0f1a39da66eedad8026dfa1dad8ca8caa424/frontend/src/composables/use-game-ai.js#L374-L421) | 源码明确 |
| F05 | **P1** | 游戏登录票据在 `connectHall()` 成功之前已存数据库，失败后的“可恢复账号”不一定真的可用 | [登录](https://github.com/xianyumht-cmd/happy4j/blob/37be0f1a39da66eedad8026dfa1dad8ca8caa424/src/main/java/cc/superbaby/happy4j/application/HappyDdzClientService.java#L176-L219) | 源码明确 |
| F06 | **P1 调查** | Lotus 仅开启操作系统 TCP keepalive，未发现与 GameServer 一样的应用层定时心跳；曾观察到接近五分钟空闲后掉线 | [Lotus](https://github.com/xianyumht-cmd/happy4j/blob/37be0f1a39da66eedad8026dfa1dad8ca8caa424/src/main/java/cc/superbaby/happy4j/infrastructure/transport/SocketLotusTransport.java#L80-L106) / [GameServer](https://github.com/xianyumht-cmd/happy4j/blob/37be0f1a39da66eedad8026dfa1dad8ca8caa424/src/main/java/cc/superbaby/happy4j/infrastructure/transport/SocketGameServerChannel.java#L87-L112) | 未证实初始断线原因，不能盲目造心跳包 |
| F07 | **P1** | 命令失败后清除去重键并重试，缺少永久错误和暂时错误的不同处理；欢乐豆不足可能重复换桌 | [重试](https://github.com/xianyumht-cmd/happy4j/blob/37be0f1a39da66eedad8026dfa1dad8ca8caa424/frontend/src/composables/use-game-ai.js#L201-L224) / [循环](https://github.com/xianyumht-cmd/happy4j/blob/37be0f1a39da66eedad8026dfa1dad8ca8caa424/frontend/src/composables/use-game-ai.js#L575-L596) | 源码明确 |
| F08 | **P1（联网部署安全）** | 发布配置有固定回退数据库密码与票据加密密钥；WebSocket 在 URL 查询串携带 bearer token | [默认值](https://github.com/xianyumht-cmd/happy4j/blob/37be0f1a39da66eedad8026dfa1dad8ca8caa424/src/main/resources/application.properties#L5-L20) / [WebSocket](https://github.com/xianyumht-cmd/happy4j/blob/37be0f1a39da66eedad8026dfa1dad8ca8caa424/frontend/src/services/game-socket.js#L15-L20) | 源码明确；不允许未备份就修改已有加密密钥 |
| F09 | **P2** | `/api/auth/me` 的任何失败都会清掉本地登录 token，包括网络暂时中断 | [API](https://github.com/xianyumht-cmd/happy4j/blob/37be0f1a39da66eedad8026dfa1dad8ca8caa424/frontend/src/services/api.js#L27-L35) | 源码明确 |
| F10 | **P2** | 胜率用 `beanChange > 0` 推断，打牌次数在服务器确认前增加；可能统计错误 | [结算](https://github.com/xianyumht-cmd/happy4j/blob/37be0f1a39da66eedad8026dfa1dad8ca8caa424/frontend/src/composables/use-game-ai.js#L432-L454) / [动作](https://github.com/xianyumht-cmd/happy4j/blob/37be0f1a39da66eedad8026dfa1dad8ca8caa424/frontend/src/composables/use-game-ai.js#L290-L323) | 源码明确 |
| F11 | **P2** | 前端把多种错误统一映射为“稍后再试”；会话监视器长期保存订阅未见清理 | [提示](https://github.com/xianyumht-cmd/happy4j/blob/37be0f1a39da66eedad8026dfa1dad8ca8caa424/frontend/src/utils/player-message.js#L1-L32) / [观察者](https://github.com/xianyumht-cmd/happy4j/blob/37be0f1a39da66eedad8026dfa1dad8ca8caa424/src/main/java/cc/superbaby/happy4j/websocket/GameRealtimeGateway.java#L18-L34) | 源码明确 |
| F12 | **P2** | `main` 没有官方 Windows 一键安装、升级、故障自查、回滚验收链；CI 分支验证不等于实际扫码登录 | [README](https://github.com/xianyumht-cmd/happy4j/blob/37be0f1a39da66eedad8026dfa1dad8ca8caa424/README.md) | 默认分支目录与工作流历史核对 |

## 已有能力，应当保留

- 已经包含六个 DouZero FullAuto ONNX 权重、三角色出牌模型和合法动作枚举。
- Java + Gradle + Vue/Vitest 等测试存在，且之前基于完全相同上游源码的恢复构建有 CI 成功记录。
- MySQL + Flyway、扫码平台授权、GameServer 连接和页面逻辑已经成体系。
- **不应把这些问题作为调整 AI 模型选牌的理由。** 原版策略当前是独立验收基线。

## 推荐审查与修复顺序

**阶段 0：可观测性和基线** — 对登录阶段、Socket 连接代数、会话 ID、失败类别和恢复路径形成不泄露票据的事件日志。先复现，不改连接生命周期。

**阶段 1：扫码完整成功条件** — 前端明确展示平台确认、游戏认证、大厅连接、钱包同步的独立状态。只有大厅连接被确认后才能提示“游戏登录成功”。修复新旧 session 的选择策略。

**阶段 2：断线后的正确恢复** — 对连接对象进行 generation-aware 安全回收；确保旧连接事件无法误删新的连接。独立调查先前约五分钟断线的真正原因，不做无凭据的协议心跳修改。

**阶段 3：AI 托管可靠性** — 大厅断开时暂停、同步恢复后再继续；永久错误停止、可恢复错误有限重试；统计服务器真正接受的动作及可靠胜负结果；保留原版 DouZero。

**阶段 4：部署与产品体验** — Windows 版本号/预检/数据库保护/日志包/失败提示/一键恢复；正式发布前必须完成真实 Windows 账户验收。

## 必须通过的端到端验收

1. Windows 10 便携环境正确启动 Java + MySQL，账号、数据库和 .env 保持完整。
2. QQ 和微信分别进行真实扫码，不能把 QR 确认误报为游戏大厅登录成功。
3. 依次确认 SafeLogin、Lotus 大厅、实时余额、进入经典场次、坐下准备、叫抢、加倍、合法出牌和结算。
4. 空闲跨越历史问题窗口、正常游玩中发生断线、重复扫码、切换 QQ/微信、旧连接晚到的回调，均不能造成永久失败或踢掉新连接。
5. 欢乐豆不足、挤号、网络短暂不通、过期票据、AI API 失败：正确区分等待、停止、重登，并且不产生请求风暴。
6. 测试无需修改 DouZero 权重、候选动作枚举或大模型提示词。
7. 用版本固定的安装包完成备份、升级和回滚；保证 MySQL 和启动配置不丢失。
8. 所有诊断日志去除密钥、票据、Token 和个人账户标识。

## 审计边界

这是**静态检查及已提供用户日志复核**，并不代表成功进行了真实腾讯账号登录、实战对局或 Windows 实机安装。历史日志显示过 Lotus 连通并更新余额，之后断开；再次获得 SafeLogin 凭证，却没有记录新的 Lotus 成功连接。此事实支持问题存在，**但尚不足以唯一确定为何断线**。此前修复版用户遇到新的扫码问题，说明那个分支不能用于生产验收。

`main` 在本次审查中保持不变；审查报告不是修复版，也不是发布承诺。

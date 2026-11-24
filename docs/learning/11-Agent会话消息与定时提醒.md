# 11：Agent 会话消息与定时提醒

[学习导航](00-学习导航.md) · [上一篇：整合运行](10-IM知识助手整合与真实运行.md) · [验证记录](../verification.md)

## 本篇目标

沿当前完整代码理解“自然语言请求 → 业务工具拟稿 → 用户确认 → 到期执行 → 会话消息”的链路。这是 2026-11-20 用户授权的功能升级，位于 IM 工程的 AiService 和 UserService，不属于原始课程第 1—14 章，也没有修改原始课程资料或独立 AI 工程。

已有能力：单聊/群聊消息草稿、立即或定时发送、个人一次性提醒、任务查询、修改、取消、失败重试。提醒复用红包的 Redis ZSET + Lua + Kafka 思路。真实模型选择工具、新提醒在真实中间件上的端到端运行尚待联调，不能把自动化测试替身说成真实模型验证。

## 1. 先从具体场景看入口

场景一：“找到项目讨论群，通知大家接口已经更新。”助手先查本人会话，找到实际 sessionId，再创建消息草稿。你在页面检查目标、正文和时间，点击确认，调度器才可以发送。单聊接收者由服务端查成员得出；同名会话需要先选择，不能让模型猜 ID。

场景二：“明天下午三点提醒我提交简历。”助手通过已有时间工具取得当前时间，将相对时间转成完整日期与时区，在本次助手会话生成提醒草稿。用户确认后，到期由机器人向本人发送 `[助手提醒] 提交简历`，不再次调用大模型。

先打开[操作页面](../../GateWay/src/main/resources/static/agent-console.html)了解实际按钮，再看[AiChatController.chat / streamChat](../../AiService/src/main/java/cn/qingliao/aiservice/controller/AiChatController.java)：登录用户或可信内部请求经过会话授权，调用[AiChatService.forActor](../../AiService/src/main/java/cn/qingliao/aiservice/ai/AiChatService.java)构建绑定本人身份的工具。原来的检索、时间、邮件和可选 MCP 能力仍在；本次增加的是内部 IM 业务工具。

## 2. 文件夹与陌生单词先认清

| 位置 / 单词 | 含义与职责 |
|---|---|
| [AiService/tool/ImActionTool](../../AiService/src/main/java/cn/qingliao/aiservice/tool/ImActionTool.java) | Tool：供模型调用的函数；这里把自然语言意图转成业务请求 |
| [AiService/client/AgentActionClient](../../AiService/src/main/java/cn/qingliao/aiservice/client/AgentActionClient.java) | Client：远程调用客户端；通过 Feign 访问 UserService |
| [skills/im-assistant/SKILL.md](../../AiService/src/main/resources/skills/im-assistant/SKILL.md) | Skill：操作规程，告诉模型何时查会话、拟稿、追问；权限仍由 Java 校验 |
| [UserService/agent](../../UserService/src/main/java/cn/qingliao/userservice/agent/AgentActionService.java) | 保存并执行 IM 业务任务，不依赖大模型持续运行 |
| draft / prepare / revise / confirm | 草稿 / 准备 / 修改 / 确认；prepare 不是发送完成 |
| owner / actor / receiver | 任务所有者 / 当前操作者 / 消息接收者 |
| [Repository](../../UserService/src/main/java/cn/qingliao/userservice/agent/AgentActionRepository.java) | 数据访问；这里用 JdbcTemplate 执行条件更新，便于直接核对状态竞争 |
| [Scheduler](../../UserService/src/main/java/cn/qingliao/userservice/agent/AgentActionScheduler.java) | 调度器；扫描、登记和分发到期任务 |
| [Delivery](../../UserService/src/main/java/cn/qingliao/userservice/agent/AgentActionDelivery.java) | 投递执行器；发布存储和推送事件 |
| version / lease / retry | 版本 / 执行租约 / 重试；分别处理旧操作、执行进程失联、临时失败 |

不需要把这些 DTO 和接口逐行背下来。优先沿下面两条链路阅读，再回来查字段。

## 3. 第一条链：模型拟稿，用户确认

1. [ImActionTool.findImSessions](../../AiService/src/main/java/cn/qingliao/aiservice/tool/ImActionTool.java)调用内部客户端，UserService 的[AgentActionService.sessions](../../UserService/src/main/java/cn/qingliao/userservice/agent/AgentActionService.java)只返回本人正常所属的会话。单聊显示对方昵称，便于识别目标；查得到会话不代表最终一定有发送权限。
2. 消息使用同文件的 `prepareSessionMessage`，个人提醒使用 `preparePersonalReminder`。个人提醒目标来自服务端绑定的本次助手会话，模型不能指定别人的提醒会话。
3. [AgentActionInternalController](../../UserService/src/main/java/cn/qingliao/userservice/agent/AgentActionInternalController.java)先验证可信内部调用身份，再让 `AgentActionService.draft` 检查目标、正文、时间、成员和发送权限，最后通过[Repository.insert](../../UserService/src/main/java/cn/qingliao/userservice/agent/AgentActionRepository.java)保存 DRAFT。草稿没有消息发送副作用。
4. [操作页面的 refresh / confirm 按钮](../../GateWay/src/main/resources/static/agent-console.html)展示草稿。用户点击后调用公开的[AgentActionController.confirm](../../UserService/src/main/java/cn/qingliao/userservice/agent/AgentActionController.java)，身份来自登录态 `CurrentUser.id()`，不从请求正文接收 userId。
5. [AgentActionService.confirm](../../UserService/src/main/java/cn/qingliao/userservice/agent/AgentActionService.java)核对最新版本、再次授权，将 DRAFT 改成 PENDING，设置下一次执行时间。立即消息的时间为空，表示确认后尽快执行；定时消息或提醒等待指定时间。

工具对象为什么每次请求创建？看[ImActionTool 的 final actorId / sourceSessionId](../../AiService/src/main/java/cn/qingliao/aiservice/tool/ImActionTool.java)：身份作为不可变字段传入，工具换线程也不会读错 ThreadLocal；跨用户共享的默认助手不装载这些绑定身份的工具。内部 RPC 的 actorId 是可信应用代传，公开接口不能伪装这个内部身份。

模型没有 confirm 工具，内部 RPC 也没有确认接口；聊天中说“确认”不会直接发送。这个设计让拟稿可以利用模型，真正执行仍有具体、可检查的用户决定。规程见[IM Skill 第 4 步](../../AiService/src/main/resources/skills/im-assistant/SKILL.md)。

## 4. 第二条链：到期扫描到消息存储与推送

```text
MySQL agent_action（已确认任务，事实来源）
  → Scheduler.dispatch 每秒扫描 / repair 每15秒补偿
  → Redis ZSET agent-action:due（score = 下次执行毫秒）
  → Lua 原子取走到期项（actionId:version）
  → Kafka agent-action-due-topic
  → Scheduler.consume → Delivery.execute 条件领取
  → store-topic：原有离线存储消费者
  → message-topic：原有实时推送消费者
```

对照[AgentActionScheduler.dispatch / repair / consume](../../UserService/src/main/java/cn/qingliao/userservice/agent/AgentActionScheduler.java)阅读。Lua 使用 Redis 时间并一次最多取 100 项；多实例并发不会同时从 ZSET 取走同一项，但 DB 补偿可能重新登记，所以消费者仍必须防重复。调度是秒级扫描，失败会补偿，不保证毫秒准时。

这里与[红包过期链](07-红包与异步账务.md)复用的是调度方法，业务表、Redis key、Topic、执行器独立。提醒不是创建一个延时线程等到明天；服务重启后可以从数据库恢复。Redis 丢项或“Lua 已取走，Kafka 尚未成功”的窗口，由数据库重新扫描弥补。

进入[AgentActionDelivery.execute](../../UserService/src/main/java/cn/qingliao/userservice/agent/AgentActionDelivery.java)，先用 `status=PENDING + version + next_attempt_at` 条件更新领取，写 RUNNING、租约 token 和 120 秒到期时间，再重新调用[AgentActionService.authorize](../../UserService/src/main/java/cn/qingliao/userservice/agent/AgentActionService.java)。本人退群、会话关闭或对方拉黑后，旧授权不能继续使用。普通消息和 Agent 共用[SessionSendAccess.canSend](../../UserService/src/main/java/cn/qingliao/userservice/agent/SessionSendAccess.java)规则，原有[SessionAccessController](../../UserService/src/main/java/cn/qingliao/userservice/controller/SessionAccessController.java)转为调用该服务。

随后构造现有 MessageRequest：代发消息以本人为 sender，前缀 `[助手代发]`；个人提醒以 AI_ID 为 sender、本人为 receiver，前缀 `[助手提醒]`。只发布存储、推送 Topic，**不发布 ai-request-topic**，因此提醒不会触发机器人自我对话。下游继续使用原有[MessageServiceImpl](../../OfflineDataService/src/main/java/cn/qingliao/offlinedataservice/service/impl/MessageServiceImpl.java)和[ConsumerMessageService](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/consumer/ConsumerMessageService.java)。

## 5. 状态、并发与失败窗口

| 状态 | 意义 / 后续动作 |
|---|---|
| DRAFT | 待用户确认；尚未执行可修改、取消 |
| PENDING | 已确认，等待时间或自动重试；未开始投递的任务可修改、取消 |
| RUNNING | 已领取执行；不能修改、取消或撤回 |
| SENT | 存储与推送两个 Kafka Topic 都已确认接收，不代表已落库、接收端收到或已读 |
| FAILED | 权限/参数失败或达到自动重试上限；可能部分投递，可核对后手工重试 |
| CANCELLED | 已取消未执行任务；旧版本事件无法领取 |

修改和取消看[AgentActionService.edit / cancel](../../UserService/src/main/java/cn/qingliao/userservice/agent/AgentActionService.java)：用版本号参与 SQL 条件竞争，修改后回到 DRAFT，版本递增，必须重新确认。开始过投递的任务即使暂时退回 PENDING，也不能改正文或取消，以免同一消息编号对应不同正文。

失败处理看[Delivery.execute 的 storeSent / pushSent 与 catch 分支](../../UserService/src/main/java/cn/qingliao/userservice/agent/AgentActionDelivery.java)：同一任务始终复用 messageId 和首次执行时间，两个 Topic 的确认标记分别保存。若存储队列已确认、推送失败，重试只补未确认的推送；最多自动尝试 5 次。执行进程失联后，[Scheduler.repair](../../UserService/src/main/java/cn/qingliao/userservice/agent/AgentActionScheduler.java)恢复过期租约。旧执行者更新标记需匹配租约 token，但租约不能阻止已发往外部系统的请求。

仍有“Kafka 已接收，数据库确认标记没写成功”的窗口，此时可能重复发布。数据库按 messageId 幂等存储，推送端仍需按同一 ID 合并；这套方案是至少一次投递，不能声称严格一次，也没有修复所有普通 WebSocket 消息的双 Topic 原子性。用户手工重试同样保留原 messageId，已确认的 Topic 不重发。

创建请求还使用 `(owner_id, request_key)` 唯一约束。同次模型请求内相同参数复用请求键；换一轮对话不保证自动合并重复语义。因此工具超时应先查询任务，不能无限盲目重新拟稿。字段和索引直接看[05-agent-actions.sql](../../database/05-agent-actions.sql)。

## 6. 如何运行与验证

1. 在当前 IM 业务数据库执行[05-agent-actions.sql](../../database/05-agent-actions.sql)。SQL 不含 USE，请先选择 `.env` 的 DB_NAME 对应库。旧数据不删除；脚本 CREATE IF NOT EXISTS 可重复执行。已有 Docker 卷不会因重启而自动执行新脚本；新空卷的初始化由[prepare-env.ps1](../../tools/prepare-env.ps1)纳入 05。
2. 在父 pom 所在目录运行 `./tools/verify.ps1 -Package`，再按[当前启动方式](10-IM知识助手整合与真实运行.md)重启 UserService、AiService、GateWay。已有实时/离线消费者、MySQL、Redis、Kafka 也需可用。定时功能要求 `chat.scheduling.enabled=true`（默认开启）。
3. 打开 `http://localhost:10010/agent-console.html`，使用现有邮箱和密码登录，加载会话。先手工拟稿并确认一条两分钟后的个人提醒，检查任务状态和原聊天页/历史，再试修改重新确认、取消未执行任务。页面只是任务操作入口，消息展示仍由原聊天页和历史接口完成。
4. 自然语言拟稿需要真实可用模型配置。现有整合启动脚本使用 `-Models` 开启模型模式；配置细节沿用上一篇。纯手工拟稿、任务确认与定时投递链不依赖大模型。当前模型未联调，不能把页面本地模拟回复当作实际模型可用。

代码验证覆盖实际迁移 SQL + H2、真实本机 HTTP 登录与权限、跨用户拒绝、时间与状态流转、旧版本事件、并发领取、部分投递补发、租约恢复和工具切线程身份。入口分别为[AgentActionTest](../../UserService/src/test/java/cn/qingliao/userservice/agent/AgentActionTest.java)、[AgentActionHttpTest](../../UserService/src/test/java/cn/qingliao/userservice/agent/AgentActionHttpTest.java)、[ImActionToolTest](../../AiService/src/test/java/cn/qingliao/aiservice/ImActionToolTest.java)、[GatewaySmokeTest](../../GateWay/src/test/java/cn/qingliao/gateway/GatewaySmokeTest.java)。准确数量和运行环境以[本轮验证记录](../verification.md)为准。

页面另用模拟 HTTP API 验证了拟稿、确认、取消、大整数 ID 保真、正文按纯文本显示和退出清理；不等于真实中间件联调。当前未实现周期提醒、cron、邮件/短信提醒、多渠道通知或 Agent 任意代用户操作。

## 本篇自查

1. Redis 已经原子取出到期任务，为什么数据库还需要条件领取？
2. 为什么定时任务创建时与执行时都要检查会话权限？
3. 存储 Topic 已成功而推送 Topic 失败，怎样重试？
4. 用户修改已确认提醒后，旧 Kafka 事件会不会继续发旧正文？
5. 任务 SENT 能否在简历里写“用户已收到且严格一次”？

## 自查解答

1. 补偿可能重新登记任务，Kafka 也可能重投。ZSET 去重不能替代数据库对状态、版本和执行时间的条件竞争。
2. 用户可能在等待期间退群、被拉黑或会话关闭，创建时的权限不能永久授权。
3. 保存两个 Topic 各自的确认标记，复用原 messageId 与首次时间，只补未确认的一路；确认标记写入前崩溃仍可能重复发布。
4. 未开始执行时修改会递增版本并回到 DRAFT，旧版本事件无法条件领取；重新确认新版本才可执行。如果执行已经领取，则修改失败，由竞争结果决定。
5. 不能。SENT 仅代表两个队列的接收确认，端到端送达/已读和严格一次都超出了现有实现与验证范围。

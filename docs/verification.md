# 验证记录与实际边界

当前材料检查：2026-11-20；Java 运行测试日期以各节为准。IM 工程，JDK 17。原始课程对照见[补齐记录](learning/核查-原始课程文档实现对照.md)。


## 2026-11-20 个人与会话知识库升级

新增用户上传的 PERSONAL / SESSION 资料归属、TXT/MD/PDF/DOCX 解析、持久化索引任务、1024 维向量片段、限定范围的向量/关键词召回、来源展示、删除及重试。普通用户助手切换到新表，旧共享写入接口停用；聊天记忆按用户、会话和可见资料版本隔离。资料页可携带当前会话进入 Agent 拟消息，个人提醒仍要求本人的助手会话。说明和逐步源码链接见[第 12 篇](learning/12-个人与会话知识库上传和权限检索.md)。

**JDK 17.0.9，标准脚本选定的 103 项回归全部通过，零失败/错误/跳过**；589 项原文映射与 281 个 Java 文件四空格检查通过，六应用 clean/package 成功。日志 `.codex-work-knowledge-package.log`。随后对最终静态页面重跑 Common/GateWay 的 9 项相关测试并打包，日志 `.codex-work-knowledge-gateway-final.log`，这 9 项是上述回归集合的重复验证，不另累计。明细见[结果记录](verification/knowledge-tests-20261120.json)。

另外显式启用 ScopedKnowledgePgTest，在本机实际运行的 PostgreSQL/pgvector/pg_search 上执行 **6 项真实数据库＋HTTP 检查，全部通过**。只创建独立随机 kb_test_ schema，执行当前迁移、建 BM25 索引、真实文件 multipart HTTP、资料/片段写入、余弦排序与关键词范围查询，验证个人/会话隔离、Top K 前过滤、非上传者拒绝删除、退群拒绝、索引失败重试、删除不被旧租约复活、过期租约恢复、并发领取和失败事务回滚。测试结束临时 schema 已清理，另查 kb_test_ 前缀残留数为 0。该套件不在默认标准脚本中自动连接外部数据库。

上述数据库测试中的 ChatModel、Embedding、精排、Redis身份校验和 UserService RPC 使用替身；UserService 的真实成员与状态判断另在本机 HTTP/H2 测试覆盖。不是整个实际部署的登录、RPC、中间件和真实模型全链路测试，也未取得检索质量分数。首次运行只有测试重新设置已抛错的 Mockito 返回值失败，修正测试桩后六项全部通过。

浏览器实际加载页面、API 使用拦截替身，验证个人上传不携带会话、共享上传绑定所选会话、提取文字与来源按文本显示、问答来源、大整数 ID、范围切换清理、删除/重试与退出身份清理；另复测 Agent 草稿确认/取消及团队会话上下文选择。使用 DOM 事件派发与确认弹窗替身。三个页面脚本通过 Node 语法检查；迁移副本与标准 SQL 字节相同，相关学习文档相对链接检查通过。

没有对实际业务 schema 执行新迁移，也没有重启既有服务或调用真实模型/Embedding/精排。启用前需在 PGVECTOR_DATABASE 对应 PostgreSQL 库执行[03 SQL](../database/postgres/03-scoped-knowledge.sql)，重启 UserService、AiService、GateWay，打开 `http://localhost:10010/knowledge.html`。新空卷的普通/服务器 Compose 与整合目录已纳入初始化；已有卷需手工迁移。若没有真实 Embedding 配置，新文件不能完成 READY 索引；已有 READY 资料的关键词查询降级不代表索引不依赖 Embedding。

旧课程 RAG、样例文件和受限评测保留，旧评测要求 dev/test 环境及维护者登录 Token；不混入用户上传库。没有实现 OCR、原文件下载、文档版本编辑、管理员接管、容量评估或真实模型质量评测。删除阻止后续检索并切换记忆，不撤回已有 IM 消息或已输出答案。

## 2026-11-20 Agent 会话消息与定时提醒升级

在 IM 工程中增加消息草稿与确认、定时发送、一次性个人提醒、任务修改/取消/重试及持久化补偿，见[第 11 篇](learning/11-Agent会话消息与定时提醒.md)。本次属于用户授权的课程外升级，原始课程资料未改。普通发送权限抽为共享服务，模型工具绑定可信用户；模型无确认工具。开始过投递的任务锁定正文，避免重试产生同 ID 不同正文。

最后使用 **JDK 17.0.9** 执行 `./tools/verify.ps1 -Package`：589 项原文材料映射检查、269 个 Java 文件 AOSP 四空格检查、六应用 clean/package 均通过；脚本选定的 **91 项回归测试通过，零失败、错误、跳过**，见[逐套件明细](verification/agent-actions-20261120.json)。这是脚本指定的回归集合，不是所有可发现测试。日志位于工程根目录 `.codex-work-agent-package-final.log`，可执行 Jar 已更新到各应用的 `target-maven`。

其中新增 AgentActionTest 10 项、AgentActionHttpTest 2 项、ImActionToolTest 3 项，覆盖实际 05 迁移 SQL 的 H2 执行、真实本机 HTTP 登录与所有权、内部身份伪造拒绝、成员与发送权限、时间/版本/确认、并发领取、旧事件、租约恢复、分 Topic 重试和部分投递后禁止改稿。另扩展 AiContextTest 验证绑定不同用户的助手可构建，新增网关测试验证无需登录即可打开操作页、业务 API 仍受鉴权保护。新任务测试中 Redis/Kafka 为模拟，不连接真实模型。

浏览器加载实际 HTML 并拦截 HTTP API，验证登录、拟稿、显式确认、取消提醒、助手回复展示、大整数 ID、文本注入处理和退出清理；操作使用 DOM 事件派发，确认弹窗由测试返回 true。Node 脚本语法、PowerShell 初始化脚本语法和文档相对链接检查通过。这些证据不能扩大为真实登录页面端到端或真实模型选工具成功。

现有数据库仍需手工选择业务库并执行 [05-agent-actions.sql](../database/05-agent-actions.sql)，重启 UserService、AiService、GateWay；新空卷初始化已纳入脚本。本轮没有执行真实库迁移、重启既有服务或验证新提醒的真实 Redis/Lua/Kafka 消费和最终历史消息。真实模型、周期提醒、多渠道通知及端到端送达 ACK 仍不属于本次通过范围。`SENT` 只代表两个 Topic 的发布得到确认；确认落库前崩溃仍可能重复发布。

## 2026-11-17 两项目整合与真实运行

IM 全量 100 项、旅行 5 项测试通过，零失败/错误/跳过，见[汇总](verification/integrated-tests-20261117.json)。真实 MySQL、Redis、Kafka、MinIO、Nacos、PostgreSQL 已运行；双节点浏览器兼容握手、消息与历史、建群、群消息、身份篡改拦截、旅行注册/登录/会话通过[接口验证](verification/integration-20261117.json)。

[账务验证](verification/ledger-20261117.json)：并发重复领取仅一条收据/入账；针对新建测试红包注入数据库失败后回滚并保留退款快照，恢复重试退款一次，重复过期不重复入账。注入触发器已清理。

真实 PostgreSQL 验证 BM25 查询和新图/Saver 读取检查点恢复；图中的模型与工具使用替身。真实模型、Embedding/精排 API、外部 MCP 与 Ragas 质量评测按本人安排后续联调。Canal/SMTP、压力容量与生产容灾未纳入通过结论。后续学习见[第 10 篇](learning/10-IM知识助手整合与真实运行.md)。

## 2026-11-17 多实例与压测材料静态检查

新增 compose-multi.yml（第二 User/Offline/Realtime 实例）、历史查询 k6 脚本、WS 连接保持 k6 脚本，以及只生成 SQL/凭证文件的测试账号工具，见[第 09 篇](learning/09-多实例部署与真实测试压测.md)。未修改 Java 业务代码。

已检查 Compose 官方 Schema、三文件环境合并、worker/node 唯一性、第二 WS 端口和 Dockerfile 路径；两个 k6 脚本通过 Node ES module 语法检查；生成 100 个测试账号文件，未执行 SQL；讲义和部署入口链接检查通过。

本节较早的静态检查阶段没有使用 Docker/k6 命令；后续 WSL Docker 已实际部署，见上节。k6 与吞吐/延迟数据仍未执行或取得。Python Schema/合并检查不能代替 Compose 实际解析，Node 语法检查不能代替 k6 运行。原文资料未修改，没有重跑 Java 测试或外部联调。

## 2026-11-01 第五轮：好友申请 HTTP/H2 验证

**28 项测试通过，零失败/错误/跳过**：[明细](parity-round5-results.json)。用户 HTTP/H2 14、群权限 9、哈希 3、公共 JWT 2。UserService 及依赖 package 通过。本轮未修改生产业务代码。

新增 3 项验证：申请/已读/通过及 101/102 通知载荷、重复通过不重复建会话；拒绝后重新申请复用记录，旧事件不误过期新申请；兜底仅过期旧的未读/已读状态、保留终态及新申请。测试使用真实本机 HTTP、H2/MyBatis，Redis/Kafka 为模拟。未重跑其他服务，也未连接真实通知链路。

## 2026-11-01 第四轮：实际 Lua 分支执行及红包回归

**17 个 Lua 场景通过**：[结果](receive-lua-results.json)。测试执行部署所用 receive-redpacket.lua 源码，Lupa 2.6 提供 Lua 引擎，Redis 命令由内存桩实现；覆盖重复领取、到期边界、grant 消失、终态优先、未提交门禁、单聊/群聊授权、最后一份与不存在。没有运行真实 Redis，未验证网络、并发原子性与 TTL 时钟。

复现：安装可选测试依赖 `python -m pip install lupa==2.6` 后执行 `python tools/test-redpacket-lua.py`。本轮实际将依赖隔离装在工作目录 .codex-work/lua-runtime，以 --runtime-path 指定。Java 不增加运行依赖。

**10 项 Java 测试通过**：[明细](parity-round4-results.json)，为公共 JWT 2、红包账务 4、分配算法 4。RedPacketService 及依赖 package 通过；更新后的可执行 Jar 已包含修正脚本。此次 Java 源码未修改，未重跑其他服务测试。

## 2026-10-31 第三轮增量验证

UserService/RedPacketService 及依赖模块重新测试打包通过。**33 项测试，零失败/错误/跳过**：[本轮明细](parity-round3-results.json)。用户模块 23 项（其中 HTTP/H2 11 项），红包 8 项，公共 JWT 2 项。首次构建因新增测试调用了接口上不存在的补发方法而编译失败，改为调用实际补发实现后红包模块重新测试打包通过。

新增 3 项验证覆盖原文方向拉黑、互相拉黑与失效关系/成员拒绝、红包首次和补发保留客户端关联 ID。H2/MyBatis/事务实际执行，Redis/Kafka 使用模拟。原文未修改，讲义及链接同步检查；本轮不把此前全工程 68 项结果说成重新执行的全量。

## 2026-10-31 第二轮原文行为复核及全模块重测

执行 tools/verify.ps1 -Package：原文材料检查、243 个 Java 文件的四空格检查、全模块 clean/package、六应用可执行 Jar 均通过。**68 项测试，零失败/错误/跳过**，详见[最新明细](test-results.json)。

本轮消息测试增加至 18 项，用户 HTTP 测试增加至 9 项。新增验证：离线热区命中零消息表查询、跨冷热边界只查冷区且边界消息不漏、空/故障缓存按原离线时间回源、登录原子取走离线时间、超过 1000 条与 168 小时的总结仍按原文传入昵称/时间/正文。其余全模块原有测试也重新运行通过。

Redis/Kafka/远程模型仍使用模拟，数据库业务测试采用 H2；本轮没有重复运行 10-28 的隔离 MySQL 检查，也没有宣称真实中间件全链路通过。完整原文对照和保留差异见[验收记录](learning/核查-原始课程文档实现对照.md)。

## 2026-10-31 历史查询缓存优先修正

原文 9.6.6 主线恢复为：近七天先 Redis，满足一页且无页尾排序歧义直接返回；不足或异常才按原始复合游标查 MySQL；冷数据不访问 Redis。两条缓存写入路径统一消息结构和 JSON。

本次执行 OfflineDataService 与依赖模块 package：**消息 14 项 + JWT 2 项，16 项通过，零失败/错误/跳过**，已生成更新后的离线服务可执行 Jar。[本次明细](history-cache-results.json)。

新增行为验证包括：缓存命中时消息表 SELECT=0、缓存不足补近期缺口、冷查询 Redis 调用=0、跨冷热边界、Redis 异常/坏数据、JSON 同分排序与复合游标、文本/红包双路径 ZSET member 一致。消息模块使用真实 H2/MyBatis、模拟 Redis；Canal 转换测试输入为模拟行，没有启动真实 Redis/Canal 服务，不把单元测试当成外部联调或压测。

## 2026-10-28 全工程验证（历史记录）

tools/verify.ps1 -Package 完成 Java 4 空格检查、clean、全模块编译、针对性测试和六应用可执行 Jar 打包，产物均在 target-maven，隔离编辑器增量编译。

**54 项 Java 测试，0 失败、0 错误、0 跳过。** [当日机器可读明细](test-results-20261028.json)。

| 模块 | 项数 |
|---|---|
| AiService | 5 |
| Common | 2 |
| GateWay | 5 |
| OfflineDataService | 5 |
| RealTimeService | 10 |
| RedPacketService | 7 |
| UserService | 20 |

- 真实本机 Spring WebFlux 网关与 HTTP 上游验证路由、凭证边界和外部身份头剥离；补齐发现模式后，以 SimpleDiscoveryClient 验证 lb:// 负载均衡路由。
- 真实 Spring MVC/H2 验证登录、群权限，以及本轮补齐的群名、头像、失败成员、分页/加入顺序、邀请/踢人/退出通知、缺参错误。通知失败的建群仍保留已提交成员。
- Netty 真实 TCP/WebSocket 及 EmbeddedChannel 验证握手、心跳、双端消息、连接替换与端口回收；Redis/Kafka/RPC 为模拟。
- H2/MyBatis 验证重复消息、同毫秒游标、缓存缺口回查与权限；红包验证并发重复事件只入账一次、流水失败回滚后重试、重复过期退款和金额分配边界（含 1001 份红包）。
- AI 上下文/知识文件边界与真实 Prometheus registry 验证；原文看板所用请求、错误、Token、耗时与 histogram bucket 均可导出，未调用付费模型。

**另有 12 项真实 MySQL 8.0.43 检查通过**，在新建的隔离临时实例执行，没有修改既有 MYSQL80/3306 数据库：[明细](reference-mysql-check.json)。覆盖最终建表、重复初始化、原文单聊/群聊样例、旧库两列迁移与回填、保留既有头像/明确类型/余额/金额、领取唯一约束，以及 Nacos 3.0.3 官方库结构重复初始化。

原文映射 589 项、156 组类对应、所有阶段业务表字段、响应字段、10 个看板面板、六应用配置/Dockerfile 等检查通过；[verify-reference.py](../tools/verify-reference.py)可复核。Compose 官方 Schema、路径与依赖、YAML/JSON/XML、PowerShell 语法和学习讲义相对链接均做静态检查，不能代替容器实际启动。

最终还补齐了原文 UserSession/Message XML 及 Session XML 映射；对 UserService、OfflineDataService 重新测试打包通过，网关发现路由及移除非原文红包份数上限的增量测试也通过。汇总仍是同一组 54 项，不把重复执行累加为新增用例。

## 未做的真实联调

本机没有可用 Docker；没有在容器中启动完整应用与依赖。真实 Redis Lua/Kafka/MinIO/Canal/Nacos 服务/PgVector、SMTP、MCP、模型及 Grafana/Prometheus 的端到端链路尚未验证。隔离 MySQL 检查验证了实际 SQL，但不是所有应用与 MySQL/Redis/Kafka 组合的并发故障验收。

部署脚本与[启动说明](../deploy/README.md)已交付。没有压测、线上数据、QPS、百万连接或性能提升百分比；不得扩大测试证据的范围。空壳 contextLoads 需要外部环境，未纳入针对性脚本。

## 原文之外的设计限制

普通消息双 Topic 非原子，客户端重试缺长期请求幂等和端到端持久化 ACK；推送按节点广播，离线补拉尚无分页。通知未实现通用 outbox，afterCommit 仍有投递窗口。两次 Token 写入未原子化，未完善多端/即时踢连接，密码仍沿用固定盐 MD5。原生浏览器握手认证、完整前端、上传审核等未交付。

红包使用测试余额，不能描述为生产支付系统；跨资源仍依赖 Redis 持久化、补偿和有限保留，没有支付渠道、完整对账审计、发送长期幂等和灾难恢复。AI 没有真实效果评估，知识文件与向量库不是共同事务，也没有管理员写入角色划分。

## 历史记录

2026-10-24 曾记录 46 项测试；2026-10-26 相关增量 12 项通过。本次发现初始/升级 SQL 与测试自建表不一致等原文缺项后已补齐，最新 54 项及隔离 MySQL 证据优先于旧记录。原文与独立 AI 工程未修改。

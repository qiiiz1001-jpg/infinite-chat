# 简历项目经历：Java / AI Agent 应用开发实习

## 本篇目标

以当前轻聊 IM 工程及其中的 AiService 为依据，突出 Java 后端与 AI 应用能力。下面正文用于简历，后面的源码对照用于面试准备。项目来源按课程项目二次开发表述；起止时间填写实际学习、开发时间，个人职责按实际参与范围删减，并准备解释、修改和演示对应功能。

## 一、简历正文

### 轻聊 AI 智能聊天与知识助手

**项目类型：课程项目二次开发｜方向：Java 后端 / AI 应用开发｜时间：填写实际起止时间**

**技术栈：** Java、Spring Boot、Spring Cloud、LangChain4j、通义千问、PgVector、Redis、Kafka、Netty、MySQL。

**项目介绍：** 面向即时通信场景，基于 Java 微服务接入大模型，提供多轮对话、知识库问答、工具调用与会话总结，将 AI 回复接入聊天消息的存储和推送链路。

**主要工作：**

- **大模型与会话记忆：** 基于 LangChain4j 接入通义千问接口，提供普通/流式对话及会话总结；按用户、会话与可见资料版本隔离 Redis 记忆，持久化最近 20 条上下文消息，资料集变化后切换记忆范围。
- **RAG 知识问答：** 实现用户上传 TXT/MD/PDF/DOCX、持久化索引任务、分段与 PgVector 入库；个人/会话资料经过成员授权，在向量和关键词 SQL 内过滤范围，再经 RRF 融合与精排，返回文件和片段来源，支持删除及失败重试。
- **Agent 工具调用：** 使用 `@Tool` 封装当前范围检索、会话查询、消息与提醒拟稿、任务修改/取消工具；绑定可信用户身份，用户明确确认后由 Redis ZSET、Lua、Kafka 执行一次性任务，结合版本、执行租约与分 Topic 确认记录处理重试。
- **AI 消息链路：** 基于 Netty/WebSocket 接入聊天，通过 Kafka 解耦消息存储、在线推送和 AI 回复生成；AI 请求采用固定消费组，按原消息 ID 缓存回复，重投时复用回复内容与消息 ID，衔接 MySQL 幂等落库。
- **模型调用观测：** 通过模型监听器与 Micrometer 采集请求、异常、Token 用量和耗时指标，提供 Prometheus 导出及 Grafana 看板配置；补充延迟直方图，使看板具备计算耗时分位数所需的指标。

> 简历排版时可删除“项目类型”这一行的说明性标签，但保留课程项目二次开发的真实来源。没有实测数据时，不额外添加性能提升、准确率或用户规模。

## 二、按岗位调整一条即可

如果岗位明显偏 Java 后端，可用下面这条替换正文第 5 条，让 AI 与后端各有侧重：

- **消息查询与授权：** 实现近七天历史消息优先读取 Redis、不足时按原游标回源 MySQL，采用时间与消息 ID 复合游标处理同毫秒翻页；结合会话成员校验和内部 RPC 认证限制消息访问范围。

如果岗位明确要求 MCP，可在完成实际联网调用演示后，再添加：

- **MCP 工具扩展：** 通过 MCP 客户端适配远程搜索工具，以可配置方式接入 AI 服务，扩展模型获取外部信息的能力。

当前 MCP 客户端配置已有代码，默认关闭，尚无真实搜索调用验收；现阶段不将这一条作为已验证成果写入正文。

## 三、简历表述对应的源码

| 简历内容 | 源码入口 | 面试时要讲清楚 |
|---|---|---|
| 模型接入、多轮记忆 | [AiChatService](../../AiService/src/main/java/cn/qingliao/aiservice/ai/AiChatService.java)、[模型配置](../../AiService/src/main/java/cn/qingliao/aiservice/config/DashScopeModelConfig.java)、[Redis 记忆配置](../../AiService/src/main/java/cn/qingliao/aiservice/config/RedisChatMemoryStoreConfig.java) | sessionId 如何区分会话；20 是消息条数窗口，不是 20 轮对话；持久化记忆不等于无限上下文 |
| 普通/流式对话、总结 | [AiChatController](../../AiService/src/main/java/cn/qingliao/aiservice/controller/AiChatController.java)、[AiChat](../../AiService/src/main/java/cn/qingliao/aiservice/ai/AiChat.java)、[总结调用入口](../../OfflineDataService/src/main/java/cn/qingliao/offlinedataservice/service/impl/MessageServiceImpl.java) | 流式能力位于 AI HTTP 接口；当前机器人 Kafka 回复链调用普通对话，不能说聊天全链路都在流式推送 |
| RAG | [ScopedKnowledge](../../AiService/src/main/java/cn/qingliao/aiservice/knowledge/ScopedKnowledge.java)、[上传/索引链](12-个人与会话知识库上传和权限检索.md)、[KnowledgeRepository](../../AiService/src/main/java/cn/qingliao/aiservice/knowledge/KnowledgeRepository.java) | 资料范围与当前成员权限；两路 SQL 在 Top K 前过滤；完整索引和 READY 同事务；最多20条融合候选、5条精排上下文，效果未实测最优 |
| 业务工具与任务执行 | [ScopedRagTool](../../AiService/src/main/java/cn/qingliao/aiservice/tool/ScopedRagTool.java)、[ImActionTool](../../AiService/src/main/java/cn/qingliao/aiservice/tool/ImActionTool.java)、[任务执行链](11-Agent会话消息与定时提醒.md) | 模型拟稿与用户确认的边界；绑定身份不依赖工具线程；至少一次发布仍可能重复，SENT不代表接收端已读 |
| AI 异步接入 | [WebSocketHandler](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/websocket/WebSocketHandler.java)、[AiReplyConsumer](../../RealTimeService/src/main/java/cn/qingliao/realtimeservice/consumer/AiReplyConsumer.java)、[消息 Mapper](../../OfflineDataService/src/main/java/cn/qingliao/offlinedataservice/mapper/MessageMapper.java) | 固定消费组与每节点推送组的区别；回答生成后缓存前崩溃仍可能重复调用模型；稳定消息 ID 保护落库，不保证所有副作用严格一次 |
| 模型观测 | [模型监听器](../../AiService/src/main/java/cn/qingliao/aiservice/Monitor/AiModelMonitorListener.java)、[指标采集](../../AiService/src/main/java/cn/qingliao/aiservice/Monitor/AiModelMetricsCollector.java)、[看板](../../deploy/grafana/dashboards/ai-monitor.json) | 采集代码、指标导出与真实线上看板有数据是不同阶段；没有压测就不能给出 P99 数值或性能提升比例 |

## 四、面试中如何介绍项目定位

可以这样开场：

> 这是我基于课程项目二次开发的 Java AI 应用项目。我主要围绕聊天业务接入大模型，串起了会话记忆、RAG 和工具调用，同时梳理了 AI 请求通过 Kafka 生成回复、再进入存储与推送的流程。这里的 Agent 主要指模型通过工具描述选择并调用 Java 工具，代码中没有多 Agent 协作或复杂任务规划。项目的一个重点是把模型能力接到已有后端业务中，并处理会话权限、重复投递和失败窗口。

当前工程包含集成的 AiService。若另外介绍上级目录中的独立 AI 工程，需要说明两者复用关系和各自新增的工作，避免把同一套 RAG、记忆和工具调用重复包装为两个独立成果。

## 五、投递前需要掌握和演示的内容

正文描述的是当前代码实现，不代表外部环境已全部联调。2026-11-20 用户资料升级的标准回归 103 项、真实 PostgreSQL 独立 schema 验证 6 项通过，覆盖本机 HTTP 上传、索引事务、范围检索、删除与恢复；模型/Embedding、精排和相关 RPC 使用替身。11 月 17 日的真实 IM 中间件证据另见[验证记录](../verification.md)。真实模型、SMTP、MCP、质量与容量评测仍未验收，不能把新功能源码与局部验证写成真实模型全链路已跑通。

投递前优先准备三个演示：一次有上下文的连续对话；一个能命中文档且能解释召回过程的知识问答；一次可观察工具选择、参数与结果的调用。邮件工具实际发送邮件需使用自己的测试收件地址。演示完成后，再依据实际结果描述“已跑通”或补充测量数据。

不要写成多 Agent 协同、模型训练/微调、已实现 Rerank、生产级支付、全局严格一次、百万连接，或编造召回率、准确率、响应时间与成本下降比例。

## 本篇自查

1. 这里为什么可以介绍工具调用式 Agent？和多 Agent 有什么区别？
2. Redis 的 20 条记忆窗口、MySQL 的历史消息和 RAG 各负责什么？
3. 固定 AI 消费组和回复缓存，是否保证模型永远只调用一次？

## 自查解答

1. 模型能根据工具描述选择 Java 方法并使用返回结果完成回答；当前没有多个 Agent 的分工、规划或协作机制。
2. Redis 记忆服务于模型多轮上下文；MySQL 保存业务消息，供历史查询、离线补拉与总结取数；RAG 检索外部知识文档补充模型上下文，三者不能混为一套存储。
3. 不保证。消费组避免每个实时节点各生成一次回答，缓存让重投复用已生成内容；模型调用后、回复缓存前崩溃，仍可能再次调用。

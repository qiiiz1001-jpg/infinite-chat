# 数据库脚本

## 2026-11-20 个人与会话知识库（PostgreSQL）

[postgres/03-scoped-knowledge.sql](postgres/03-scoped-knowledge.sql)创建 kb_document、kb_chunk 和范围/任务索引，存储个人/会话归属、解析文字、索引状态和 1024 维片段向量。请在 `.env` 的 PGVECTOR_DATABASE 对应库执行，**不是 MySQL 脚本**。默认 chat_vectors；已有卷需要手工迁移，新空卷已接入普通/服务器 Compose 与整合初始化目录。

脚本不删旧 chat_embeddings，不把无归属旧资料自动变成全员可见内容。vector 扩展必需；已安装 pg_search 时创建 BM25 索引，否则应用用带相同权限过滤的关键词子串查询降级。当前上传资料和旧课程示例分表，普通用户对话只检索前者。

本轮在真实 PostgreSQL/pgvector/pg_search 的独立随机 schema 执行本脚本及 6 项索引、检索、HTTP、删除与恢复验证，全部通过并清理；模型/Embedding 为替身，未迁移实际业务 schema。说明见[第 12 篇](../docs/learning/12-个人与会话知识库上传和权限检索.md)。

## MySQL 课程与 Agent 脚本

MySQL 8.0 按顺序执行：

1. [01-init.sql](01-init.sql)：10 张业务表的最终结构，包含原文后续章新增的 session.avatar、red_packet.session_type，不删库表。
2. [02-business-upgrade.sql](02-business-upgrade.sql)：旧库补这两列、回填缺失值，增加领取唯一约束、毫秒消息时间与系统通知表；可重复执行。
3. [03-demo-data.sql](03-demo-data.sql)：两个演示账号、单聊、机器人与测试余额；不重置已有记录。
4. [04-course-examples.sql](04-course-examples.sql)：原文第 7.1 章用户 11/12/21/22/23、单聊 1、群聊 2；密码 123456，邮箱 course-对应ID@example.com，补双向正常好友以满足当前发送授权。
5. [05-agent-actions.sql](05-agent-actions.sql)：2026-11-20 新增 Agent 消息草稿、一次性提醒与投递记录表；属于用户授权升级，非原始课程内容。先选中 `.env` 的 DB_NAME 对应业务库再执行，SQL 本身不含 USE，CREATE IF NOT EXISTS 不删除已有表。

已有数据库不必重跑建表；先备份、检查重复领取记录后执行 02。升级只回填空头像与缺失会话类型，不覆盖明确已有值；无法关联会话的旧红包保留 NULL 待核对。唯一约束若因历史重复记录创建失败，应核对余额与流水，不自动删除金融记录。

Agent 升级：已有业务库只需另执行 05，重启 UserService、AiService、GateWay。prepare-env 已把 05 纳入新空卷初始化；已有卷不会重放初始化 SQL，不能以重启代替迁移。本轮 05 已通过 H2 MySQL 兼容模式执行验证，尚未对真实 MySQL 应用这次新迁移。

初始化默认库名 Qingliao。tools/prepare-env.ps1 根据 .env 生成副本，不执行 SQL；Docker MySQL 只在空卷首次启动时自动执行 generated/mysql。已有卷需手动迁移，不能靠重启重放 init 目录。

Nacos 外置 MySQL：[01-schema.sql](nacos/01-schema.sql)使用当前镜像对应的官方 3.0.3 结构，CREATE IF NOT EXISTS；[原始上游文件](nacos/upstream-3.0.3.sql)保留许可，[来源与 SHA256](nacos/SOURCE.json)可核对。prepare-env 生成独立 Nacos 用户授权，不复制原文 DROP DATABASE 或默认密码。

本次使用独立 MySQL 8.0.43 临时实例验证新库、旧库、重复执行、原值保留及 Nacos 结构，共 12 项通过，[结果记录](../docs/reference-mysql-check.json)。没有修改既有 MYSQL80/3306 数据库；完整应用和其他中间件仍未联调。启动见[部署说明](../deploy/README.md)。

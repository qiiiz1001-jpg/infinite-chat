# 本机启动与业务演示

多实例与压测见[第 09 篇](../docs/learning/09-多实例部署与真实测试压测.md)，新增 compose-multi.yml 应叠加在基础文件与 compose-discovery.yml 之后；不要对固定 worker/端口的实例直接 --scale。

以下命令在包含父 pom.xml 的 IM 工程根目录执行。环境要求 JDK 17、Maven 3.9、Docker Desktop（Linux 容器及 Compose 插件）；Python 可用于原文材料静态核对。目前机器没有可用 Docker 命令，本次没有启动这些容器，也没有操作现有 MySQL。

## 1. 准备配置与构建

```powershell
./tools/prepare-env.ps1
./tools/verify.ps1 -Package
./tools/preflight.ps1
```

脚本生成本机 `.env` 与 `deploy/generated/`；它们已加入忽略规则，密码不打印。再次执行会保留原 `.env`，更新初始化文件。默认数据库端口 **3307**，避免占用已有 3306。需要其他端口时同时修改 `.env`；Kafka 广播地址默认 localhost:9092，不可只改宿主端口。

`verify` 校验 4 空格格式，并将六个应用打包到各自 `target-maven/`。也可单独运行 `./tools/format-java.ps1` 格式化，`-Check` 仅检查。

## 2. 启动基础设施

```powershell
docker compose --env-file .env -f deploy/compose.yml config --quiet
docker compose --env-file .env -f deploy/compose.yml up -d mysql redis zookeeper kafka minio
docker compose --env-file .env -f deploy/compose.yml ps
```

MySQL 新卷首次启动会按顺序执行生成目录内 01 建表、02 升级、03 演示数据、04 原文单聊/群聊示例、04 Canal 用户、05 Nacos 表和 06 Nacos 用户脚本。已有数据卷不会重放初始化；需通过数据库客户端手动执行 [升级脚本](../database/02-business-upgrade.sql)。不需要删除数据卷重建。红包服务启动时检查领取唯一索引，缺少时拒绝启动。

演示账号 `demo-a@example.com`、`demo-b@example.com`，密码均为 `123456`；用户 ID 1001、2002，单聊会话 99001。各有 100000 分测试余额；机器人会话 99002 只属于 A。演示数据脚本不重置已有余额。另有原文第 7.1 章的 11/12/21/22/23 五账号，邮箱为 course-对应ID@example.com，密码 123456；单聊 1、群聊 2 可用于原文推送示例。

MinIO 9000 为对象接口，9001 为控制台。用户服务启用初始化时创建 `infinitechat` 桶，仅公开 `users/` 头像对象的读取；PUT 仍需短期签名。容器内初始化地址为 `http://minio:9000`，返回给本机客户端的签名使用 `http://localhost:9000`，二者不能混用。CORS 允许本机 5173、10010，配置依据 [MinIO 源码](https://github.com/minio/minio/blob/master/internal/config/api/api.go)。

## 3. 启动 Java 应用

方式一：各开一个终端执行，下列进程会持续占用终端。

```powershell
./tools/run-service.ps1 -Module UserService
./tools/run-service.ps1 -Module OfflineDataService
./tools/run-service.ps1 -Module RedPacketService
./tools/run-service.ps1 -Module RealTimeService
./tools/run-service.ps1 -Module GateWay
```

Java 本机启动需要桶初始化时，先在用户服务终端设置 `$env:MINIO_INITIALIZE='true'`。启动脚本加载 `.env`，不在启动流程执行删库或建表。

方式二：直接运行打包后的应用容器。

```powershell
docker compose --env-file .env -f deploy/compose.yml --profile apps up -d --build
docker compose --env-file .env -f deploy/compose.yml logs --tail 80 userservice realtimeservice
```

两种方式选一种，避免重复占端口。容器对本机公开网关 **10010**、WebSocket **9101**；业务 HTTP 端口在容器网络内通信。本机 Java 端口：Offline 8101、Realtime 8102、RedPacket 8103、User 8104、AI 8105。

默认关闭 Nacos，服务间使用显式地址，登录返回单节点 Netty 地址。启用 discovery profile 并将 .env 的 NACOS_ENABLED 改 true 后，使用原文同类的外置 MySQL 持久化方案：prepare-env 生成 05-nacos-schema.sql 与 06-nacos-user.sql，独立库与随机用户密码由环境配置。已有 MySQL 卷先手动执行这两个文件；默认库 nacos_config，不删除既有数据库。SQL 使用 [Nacos 3.0.3 官方结构](https://github.com/alibaba/nacos/blob/3.0.3/distribution/conf/mysql-schema.sql)，不将旧版本表结构直接套用。扩多个实时节点必须分别配置 CHAT_NODE_ID、SNOWFLAKE_WORKER_ID 和可访问的 ws-host/ws-port，不能复制同一个实例配置。

## 4. 验证最小链路

若要还原原文的“网关 lb:// 路由 + Feign 服务名发现”，需叠加 [compose-discovery.yml](compose-discovery.yml)，不只启动 Nacos：

```powershell
docker compose --env-file .env -f deploy/compose.yml -f deploy/compose-discovery.yml --profile apps --profile discovery up -d --build
```

叠加文件强制开启发现、让 Feign 不再固定到 URL，并将网关路由设为 lb://服务名。本机 Java 方式可在各启动命令增加 -Discovery，例如 ./tools/run-service.ps1 -Module GateWay -Discovery；Nacos 服务应先就绪。网关测试已用真实 HTTP 上游与 SimpleDiscoveryClient 验证 lb:// 路由，不代替真实 Nacos 注册验证。

```powershell
./tools/smoke-api.ps1
```

该脚本实际检查：网关登录 → 查询演示会话历史 → 根据登录返回地址建立 WebSocket → ping/pong，不打印 Token。启动失败时先看服务日志，再检查 [数据库说明](../database/README.md) 与 `preflight.ps1`；端口可达不等于业务可用。

手动演示聊天时，分别登录 A、B，将响应 accessToken 设置为 WebSocket 的 `Authorization: Bearer <token>` 握手头。普通浏览器原生 WebSocket API 不能设置自定义握手头，当前可使用支持握手头的客户端或 .NET ClientWebSocket；浏览器兼容认证方案尚未实现。

连接到登录返回的 nettyUri 后，A 发以下文本帧（ID 按字符串传输）：

```json
{"sessionId":"99001","sessionType":0,"receiverId":"2002","type":0,"clientMessageId":"demo-001","content":"你好，这是一次端到端验证"}
```

应观察双方收到同一个服务端 messageId，消息随后可从 `POST /api/message/history` 查询。HTTP 受保护请求携带 `Access-Token`；首次历史请求必须带当前毫秒时间 beforeTime，例如 `{"sessionId":"99001","beforeTime":1790000000000,"limit":20}`（时间仅示意，运行时取当前时间；smoke-api.ps1 已自动取值）。翻页带末条的 createdTime 作为 beforeTime，同时带 messageId 作为 beforeMessageId，避免同毫秒漏数据。断开 B、A 再发送、B 登录后调用 `/api/message/offline`，可检验补拉。普通消息尚无客户端重试幂等协议，重复 clientMessageId 不能保证去重。

红包使用 HTTP `POST /api/chat/redPacket/send`，由 A 携带 Token 发送：

```json
{"sessionId":"99001","sessionType":0,"senderId":"1001","receiverId":"2002","type":3,"clientMessageId":"packet-demo-001","body":{"redPacketType":0,"totalAmount":1.00,"totalCount":1,"redPacketWrapperText":"测试红包"}}
```

B 调用 `/api/chat/redPacket/receive`，请求体 `{"redPacketId":"返回的红包ID","userId":"2002"}`。等待异步入账后核对领取记录、余额和流水各一次。重复领取不应重复加余额。不要通过重跑演示数据补余额；这是测试账本，未接入支付。

## 5. 可选 AI、Canal 与监控

2026-11-20 用户资料升级：先在 PGVECTOR_DATABASE 对应 PostgreSQL 库执行[03-scoped-knowledge.sql](../database/postgres/03-scoped-knowledge.sql)，使用新 Jar 重启 UserService、AiService、GateWay。打开 `http://localhost:10010/knowledge.html`，选择个人或会话范围上传文件，等待 READY 后检索/问答。新空卷已纳入初始化，已有卷不能靠重启代替迁移。Embedding 必须可用才能完成新文件索引；详细权限与实际验证见[第 12 篇](../docs/learning/12-个人与会话知识库上传和权限检索.md)。

AI 需要在 `.env` 填 DASHSCOPE_API_KEY，启动 PgVector 后运行 AiService。用户资料通过上传索引队列处理。RAG_LOAD_ON_STARTUP=true 只用于显式导入旧课程示例库供受限评测，默认关闭，也不再由整合脚本的 Models 模式自动开启。上传索引、检索和问答可能调用模型 API。

```powershell
docker compose --env-file .env -f deploy/compose.yml up -d pgvector
docker compose --env-file .env -f deploy/compose.yml --profile ai up -d --build aiservice
```

Java 模式用 `./tools/run-service.ps1 -Module AiService`。A 在会话 99002 发送 sessionType=2 消息，回复经过独立 AI 请求消费组，再进入普通存储与推送；会话总结接口为 `/api/message/summary`。MCP_ENABLED 默认 false，只有配置相应服务与 BIGMODEL_API_KEY 后才开启。邮箱验证码与邮件工具还需要自己的 SMTP 账号和授权码；密码登录演示不依赖邮件。

Canal：将 CANAL_ENABLED=true，再启用 `--profile cdc`；已有 MySQL 卷需先执行生成的 04 用户授权脚本。Canal 连接 MySQL binlog，应用订阅 `Qingliao.message`；直接消费落库后的缓存更新已能工作，Canal 是额外同步路径。

监控：`--profile monitoring up -d prometheus grafana`，Prometheus 9090、Grafana 3000；当前抓取 AI 指标，需 AI 服务已启动。原文“AI 监控看板”的全部 10 个 panel 已恢复到 grafana/dashboards/ai-monitor.json，provider.yml 会自动加载，数据源固定 UID 为 infinitechat-prometheus。Timer 已开启直方图，供 P50/P90/P99 查询使用；已有 registry 测试，但未运行真实模型/Grafana 联调。

停止容器可使用 `docker compose --env-file .env -f deploy/compose.yml --profile apps --profile ai --profile cdc --profile discovery --profile monitoring down`，不加 `-v`，保留卷数据。


## 6. 对应原文的服务器部署与工具配置

本机 Compose 将端口限制在回环地址；服务器使用 [compose-server.yml](compose-server.yml)，只对外发布网关 10010、WebSocket 9101、对象接口 9000，数据库、消息队列与控制台保持本机访问。先在服务器 .env 设置可被客户端访问的 NETTY_ADVERTISED_HOST、NETTY_URI、MINIO_ENDPOINT，并按服务器防火墙/安全组开放上述业务端口。localhost 不能当远程客户端地址。

```powershell
docker compose --env-file .env -f deploy/compose-server.yml -f deploy/compose-discovery.yml --profile apps --profile ai --profile discovery --profile cdc --profile monitoring up -d --build
```

按需选择 profile；AI、SMTP、MCP 仍需要自己的凭证。上面的命令是部署材料，不表示已替你在服务器执行；本机缺少 Docker，容器步骤尚未实测。准备 JDK 17、Maven 3.9 后，用 java -version、mvn -version、docker version、docker compose version 确认环境，再按本说明准备/构建/启动。具体安装过程可对照原始第 14 章，不修改已有全局环境。

[maven-settings.example.xml](maven-settings.example.xml)提供原文 mirror 配置，可显式 mvn -s deploy/maven-settings.example.xml 使用，不覆盖自己的 settings.xml。[docker-daemon.example.json](docker-daemon.example.json)提供镜像源配置入口，registry-mirrors 按实际可用的网络/组织镜像源填写；不要把旧镜像源或原文个人服务器凭证照搬到生产。

检查原文材料：python tools/verify-reference.py。589 个代码块与当前文件的对应见 [reference-coverage.json](../docs/reference-coverage.json)，源文件被改动时会提示重新核对。映射和结构检查不代替真实启动与故障测试。

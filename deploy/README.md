# 部署与配置

部署多个实时节点时，将 [compose-multi.yml](compose-multi.yml) 叠加在基础文件和 [compose-discovery.yml](compose-discovery.yml) 之后。为每个节点分配独立的 worker ID 和端口。

以下命令在包含父 pom.xml 的 IM 工程根目录执行。需要 JDK 17、Maven 3.9、Node.js 22.18+ 和 Docker Compose。

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

MySQL 新卷首次启动会按顺序执行生成目录内 01 建表、02 升级、03 演示数据、04 扩展演示数据、04 Canal 用户、05 Nacos 表和 06 Nacos 用户脚本。已有数据卷不会重放初始化；需通过数据库客户端手动执行 [升级脚本](../database/02-business-upgrade.sql)。不需要删除数据卷重建。红包服务启动时检查领取唯一索引，缺少时拒绝启动。

演示账号 `demo-a@example.com`、`demo-b@example.com` 和 `demo-c@example.com`，密码均为 `123456`；用户 ID 1001、2002、3003，A 和 B 有预置单聊。各有 100000 分测试余额；机器人会话 99002 只属于 A。演示数据脚本不重置已有余额。

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

默认关闭 Nacos，服务间使用显式地址，登录返回单节点 Netty 地址。启用 discovery profile 并将 .env 的 NACOS_ENABLED 改 true 后，使用外置 MySQL 持久化：prepare-env 生成 05-nacos-schema.sql 与 06-nacos-user.sql，独立库与随机用户密码由环境配置。已有 MySQL 卷先手动执行这两个文件；默认库 nacos_config，不删除既有数据库。SQL 使用 [Nacos 3.0.3 官方结构](https://github.com/alibaba/nacos/blob/3.0.3/distribution/conf/mysql-schema.sql)，不将旧版本表结构直接套用。扩多个实时节点必须分别配置 CHAT_NODE_ID、SNOWFLAKE_WORKER_ID 和可访问的 ws-host/ws-port，不能复制同一个实例配置。

## 4. 可选服务发现

使用 Nacos 服务发现时，叠加 [compose-discovery.yml](compose-discovery.yml)。

```powershell
docker compose --env-file .env -f deploy/compose.yml -f deploy/compose-discovery.yml --profile apps --profile discovery up -d --build
```

叠加文件强制开启发现、让 Feign 不再固定到 URL，并将网关路由设为 lb://服务名。本机 Java 方式可在各启动命令增加 -Discovery，例如 ./tools/run-service.ps1 -Module GateWay -Discovery；Nacos 服务应先就绪。

## 5. 可选 AI、Canal 与监控

已有知识库数据库升级时，先在 PGVECTOR_DATABASE 对应 PostgreSQL 库执行[03-scoped-knowledge.sql](../database/postgres/03-scoped-knowledge.sql)，使用新 Jar 重启 UserService、AiService、GateWay。打开轻聊，在知识库选择个人或会话范围上传文件，索引就绪后即可检索与问答。新空卷已纳入初始化，已有卷不能靠重启代替迁移。上传文件后需要通过 Embedding 服务生成索引。

AI 需要在 `.env` 填 DASHSCOPE_API_KEY，启动 PgVector 后运行 AiService。用户资料通过上传索引队列处理。RAG_LOAD_ON_STARTUP=true 用于导入内置示例资料，默认关闭。上传索引、检索和问答可能调用模型 API。

```powershell
docker compose --env-file .env -f deploy/compose.yml up -d pgvector
docker compose --env-file .env -f deploy/compose.yml --profile ai up -d --build aiservice
```

Java 模式用 `./tools/run-service.ps1 -Module AiService`。A 在会话 99002 发送 sessionType=2 消息，回复经过独立 AI 请求消费组，再进入普通存储与推送；会话总结接口为 `/api/message/summary`。MCP_ENABLED 默认 false，只有配置相应服务与 BIGMODEL_API_KEY 后才开启。邮箱验证码与邮件工具还需要自己的 SMTP 账号和授权码；密码登录演示不依赖邮件。

Canal：将 CANAL_ENABLED=true，再启用 `--profile cdc`；已有 MySQL 卷需先执行生成的 04 用户授权脚本。Canal 连接 MySQL binlog，应用订阅 `Qingliao.message`；直接消费落库后的缓存更新已能工作，Canal 是额外同步路径。

监控：`--profile monitoring up -d prometheus grafana`，Prometheus 9090、Grafana 3000；当前抓取 AI 指标，需 AI 服务已启动。Grafana 自动加载 grafana/dashboards/ai-monitor.json，数据源 UID 为 infinitechat-prometheus。计时指标支持 P50、P90、P99 查询。

停止容器可使用 `docker compose --env-file .env -f deploy/compose.yml --profile apps --profile ai --profile cdc --profile discovery --profile monitoring down`，不加 `-v`，保留卷数据。

## 6. 服务器部署

本机 Compose 将端口限制在回环地址；服务器使用 [compose-server.yml](compose-server.yml)，只对外发布网关 10010、WebSocket 9101、对象接口 9000，数据库、消息队列与控制台保持本机访问。先在服务器 .env 设置可被客户端访问的 NETTY_ADVERTISED_HOST、NETTY_URI、MINIO_ENDPOINT，并按服务器防火墙/安全组开放上述业务端口。localhost 不能当远程客户端地址。

```powershell
docker compose --env-file .env -f deploy/compose-server.yml -f deploy/compose-discovery.yml --profile apps --profile ai --profile discovery --profile cdc --profile monitoring up -d --build
```

按需选择 profile；AI、SMTP、MCP 仍需要自己的凭证。

[maven-settings.example.xml](maven-settings.example.xml)提供 Maven 镜像配置示例，可显式 mvn -s deploy/maven-settings.example.xml 使用，不覆盖自己的 settings.xml。[docker-daemon.example.json](docker-daemon.example.json)提供镜像源配置入口，registry-mirrors 按实际可用的网络/组织镜像源填写。

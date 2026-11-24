# 轻聊 IM

轻聊是一个面向小团队的即时通讯和协作应用。用户可以管理联系人与群聊、收发消息、翻阅历史、使用站内余额发红包，并通过知识库和任务面板完成资料问答、消息拟稿及定时提醒。

## 本地运行

需要 JDK 17、Maven、Node.js 22.18+ 和本机 WSL Docker。以下命令在包含父 `pom.xml` 的轻聊工程目录执行。

```powershell
./tools/start-local.ps1 -StartMiddleware -Build
```

打开 [本机应用](http://localhost:10010/)。启动脚本读取本工程 `.env`，保留已有本机数据卷，并补齐任务和知识库所需的数据库表。关闭运行脚本的终端可停止本轮启动的应用。

在本机 `.env` 配好 `DASHSCOPE_API_KEY` 后，可以启用真实模型；需要联网搜索时，同时填 `BIGMODEL_API_KEY` 并加 `-Mcp`。

在左侧打开「AI 助手」，用输入框上方的「参考会话」选择单聊或群聊。助手可按需读取该会话的历史消息和已共享的资料，普通会话的参考范围由成员权限决定。

```powershell
./tools/start-local.ps1 -StartMiddleware -Models -Mcp
```

邮箱验证码需要 SMTP 服务器地址、端口、邮箱和授权码。TLS 邮箱使用 `SPRING_MAIL_SSL_ENABLED=true` 时，应配置对应的 SSL 端口。详见[部署说明](deploy/README.md)。

## 前端开发

Vue 3 源码在 [frontend](frontend)。Maven 打包时会调用 Vite，启动后从 `http://localhost:10010/` 打开页面。

单独调试页面时，先启动 Java 服务，再运行下面的命令。Vite 会把 API 请求转发到本机后端。

```powershell
cd frontend
npm ci --cache .npm-cache
npm run dev
```

开发地址为 `http://localhost:5173/`。

## 工程入口

- [验证记录](docs/verification.md)记录后端覆盖及验证边界。
- [业务学习导航](docs/learning/00-学习导航.md)按完整链路整理源码入口。
- [数据库初始化说明](database/README.md)记录迁移和演示数据的位置。

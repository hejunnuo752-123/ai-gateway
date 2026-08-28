# AI Gateway

一个轻量级的自托管 AI 网关，用 OpenAI / Anthropic 兼容协议统一代理多家上游大模型平台，并提供 Web 管理后台。

- 技术栈：Spring Boot 3.2.5 + Thymeleaf + OkHttp
- 运行时：Java 17+
- 存储：本地 JSON 文件（无需数据库）
- 默认端口：`8822`

## 核心能力

| 能力 | 说明 |
| --- | --- |
| 多上游供应商管理 | 配置多个 OpenAI 兼容上游，支持健康检查与状态监控 |
| 虚拟模型 | 对外只暴露统一模型名（默认 `claude-sonnet-4-6`），屏蔽上游真实模型 |
| 资源组与密钥隔离 | 每个组独立 API Key、独立上游成员列表与优先级，按组路由 |
| 双协议兼容 | 同时提供 OpenAI（`/v1/chat/completions`）与 Anthropic（`/v1/messages`）接口 |
| 流式转发 | SSE 流式响应透传 |
| Web 管理后台 | 供应商 / 资源组 / AI 平台导航 / 转发日志实时查看 |
| AI 平台导航 | 内置 33 个 AI 平台官网清单，按分类浏览 |

## 快速开始

```bash
git clone https://github.com/hejunnuo752-123/ai-gateway.git
cd ai-gateway

# 准备运行时配置（仓库中不含真实密钥）
cp data/providers.json.example data/providers.json
cp data/groups.json.example   data/groups.json
# 编辑上面两个文件，填入你自己的上游 baseUrl / apiKey

mvn clean package -DskipTests
java -jar target/ai-gateway-1.0.0.jar
```

访问 `http://localhost:8822`，首次启动会自动创建默认管理员 `admin`（初始口令见启动日志，登录后请立即修改）。

## API 用法

全局入口（使用默认组密钥）：

```bash
curl http://localhost:8822/v1/chat/completions \
  -H "Authorization: Bearer <你的组 API Key>" \
  -H "Content-Type: application/json" \
  -d '{"model":"claude-sonnet-4-6","messages":[{"role":"user","content":"你好"}]}'
```

按组入口（多租户隔离）：

```
POST /g/{groupKey}/v1/chat/completions
POST /g/{groupKey}/v1/messages
GET  /g/{groupKey}/v1/models
```

## 目录结构

```
src/main/java/com/aigateway/
├── config/       认证过滤器、会话令牌、虚拟模型配置
├── controller/   OpenAI / Anthropic 协议入口、管理 API、页面路由
├── service/      转发、路由、协议转换、上游模型拉取
├── store/        JSON 文件持久化
└── model/        Provider / ResourceGroup / ModelConfig / User 等
data/             运行时数据（真实配置已被 .gitignore 排除）
```

## 配置

`src/main/resources/application.yml`：

```yaml
server:
  port: 8822

ai-gateway:
  data-dir: data
  virtual-models:
    - model-name: claude-sonnet-4-6
      context-length: 200000
      supports-vision: true
```

## Docker 部署

```bash
# 准备运行时配置（会绑定挂载进容器）
mkdir -p data logs
cp data/providers.json.example data/providers.json
cp data/groups.json.example   data/groups.json

docker compose up -d --build
docker compose logs -f          # 查看启动日志与默认管理员口令
```

访问 `http://<宿主机IP>:8822`。

也可以不用 compose：

```bash
docker build -t ai-gateway:1.0.0 .
docker run -d --name ai-gateway \
  -p 8822:8822 \
  -v "$PWD/data:/app/data" \
  -v "$PWD/logs:/app/logs" \
  -e TZ=Asia/Shanghai \
  ai-gateway:1.0.0
```

镜像要点：

| 项 | 说明 |
| --- | --- |
| 构建 | 多阶段，`maven:3.9-eclipse-temurin-17` 编译 → `eclipse-temurin:17-jre-alpine` 运行 |
| 卷 | `/app/data`（供应商、资源组、用户、背景图）与 `/app/logs` 必须挂载，否则重建容器丢配置 |
| 用户 | 以非 root `appuser`(uid 1000) 运行 |
| 健康检查 | `curl /login`（该路径在鉴权白名单内，未登录返回 200） |
| 内存 | `-XX:MaxRAMPercentage=75`，跟随容器 limit 自动伸缩，不写死 `-Xmx` |
| 端口/数据目录 | 通过 `SERVER_PORT`、`AI_GATEWAY_DATA_DIR` 环境变量覆盖 |

宿主机挂载目录的属主需允许 uid 1000 写入。若遇权限报错：`sudo chown -R 1000:1000 data logs`。

## 安全说明

以下文件包含密钥与口令哈希，已通过 `.gitignore` 排除，**请勿提交到任何公开仓库**：

```
data/providers.json      上游 API Key
data/groups.json         下游组 API Key
data/group-members.json  组成员映射
data/users.json          管理员口令哈希与盐值
data/settings.json       站点设置
data/backgrounds/        用户上传的背景图
logs/                    运行日志（可能含请求内容）
```

管理后台默认无 HTTPS，若对外暴露请置于反向代理之后并启用 TLS。

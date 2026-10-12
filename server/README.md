# 橙子短剧自建后端（chengzi-backend）

零原生依赖的轻量后端：注册 / 登录 / 改密 / 头像 / 片单云同步。
数据全部落在挂载卷 `/data`（JSON 文件 + 头像文件），删容器不丢数据。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/health | 健康检查 |
| POST | /api/register | `{username,password}` → `{token,user}` |
| POST | /api/login | `{username,password}` → `{token,user}` |
| GET | /api/me | Bearer token → `{user}`（冷启动恢复会话） |
| POST | /api/logout | Bearer → 204 |
| POST | /api/change-password | Bearer + `{oldPassword,newPassword}` → 换发 `{token,user}` |
| POST | /api/avatar | Bearer + `{data:<base64 JPEG>}` → `{user}` |
| GET | /api/avatar/:id.jpg | 头像文件 |
| GET | /api/library?source=hongguo\|huangguo | Bearer → `{payload}`（远端快照） |
| PUT | /api/library | Bearer + `{source,payload}` → `{synced:true}` |

规则与 APK 客户端一致：用户名 `[A-Za-z0-9_]{3,24}`，密码 ≥ 8 位。
密码用 Node 内置 `crypto.scrypt` 加盐哈希，不落明文。Token 为 32 字节随机值，90 天无活动自动过期。

## 部署（NAS / 任意 Docker 主机）

```bash
# 在项目根目录（含 docker-compose.yml）
docker compose up -d --build
```

- 端口：`8080`（可用环境变量 PORT 改）
- 数据卷：`./chengzi-data:/data`（备份这一个目录即可）
- 容器名：`chengzi-backend`，`restart: unless-stopped`

验证：`curl http://<NAS-IP>:8080/api/health`

## APK 对接

APK 通过 `app/src/main/res/values/config.xml` 的 `api_base_url` 指向本后端。
APK 不内置任何后端地址，登录 / 注册时由用户填写服务器地址（自建后端地址需与 APK 所在网络可达）。
改域名/端口后：改 `config.xml` + `network_security_config.xml` 对应放行条目，重新打包。

## 本地开发测试

```bash
cd server
npm install
PORT=18555 DATA_DIR=/tmp/cgdata node index.js
node test/smoke.js   # 16 项冒烟测试
```

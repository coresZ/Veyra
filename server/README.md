# Veyra 自建后端（veyra-backend）

Veyra 短剧聚合播放器的轻量后端：账号 / 动态码 / 下载任务 / 用户管理 / 管理台。
数据全部落在挂载卷 `/data`（JSON 文件 + 头像文件），删容器不丢数据。
与旧「橙子短剧（chengzi-backend）」为独立命名：镜像 `gybeyond/veyra-backend`、容器 `veyra-backend`、数据卷 `./veyra-data:/data`。

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
- 数据卷：`./veyra-data:/data`（备份这一个目录即可）
- 产物卷：`./veyra-video:/video`（NAS 下载合并产物，固定 /video 挂载）
- 容器名：`veyra-backend`，`restart: unless-stopped`
- 管理台密码：环境变量 `ADMIN_PASSWORD`（未配置则管理功能不可用）

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

# Veyra

Veyra 是一个短剧聚合播放器，由 **Android 客户端（APK）+ 自建 Node 后端（Docker）** 组成，支持红果/黄果等短剧源的在线播放、私密模式鉴权、代理绕行与手机 / NAS 下载合并。

> 本项目为 [MrSsg/HuangguoAPP-for-Andriod](https://github.com/MrSsg/HuangguoAPP-for-Andriod) 的二次开发版（GPL-3.0）。内容版权归各内容源所有，本项目仅提供技术实现，请勿用于非法用途。

---

## 功能特性

- **多源播放**：红果 / 黄果短剧在线播放；后端地址由用户在登录 / 注册页自填，**不内置任何私有域名**。
- **账号体系**：登录 / 注册（注册开关在 WebUI 动态控制），账号与设备无关。
- **私密模式**：进入私密模式需后端下发的动态码（OTP，鉴权一次、跟账号走）；**管理员账号免动态码直进**。
- **代理绕行**：设置中可配置代理端口，仅对黄果内容生效；代理不通时自动经后端转发加载，红果不受影响。
- **角色权限**：WebUI 可把用户设为 **普通用户 / 管理员**——普通用户只能下载到手机，管理员可下载到 NAS。
- **下载与合并**：
  - 手机端：下载到本地，优先合并为单个文件（合并失败自动回退为分集导出，下载不白费）。
  - NAS 端：由后端 ffmpeg 下载、解密并合并（avc3 + 时间基统一），产物直接可播。
  - "我的下载"：状态（下载中 / 已下载 / 已合并 / 失败）、进度、删除一应俱全。
- **WebUI 管理台**：用户列表（用户名 / 密码 / 手机型号等）、角色切换、删除用户、注册开关——进入需管理员密码。

## 项目结构

```
├── app/                 # Android 客户端源码（Java + WebView UI）
│   └── src/main/
│       ├── java/com/veyra/gybeyond/   # 主程序（播放、下载、账号、代理、热更新）
│       └── assets/                    # WebView 前端（app.js / app.html / css）
├── server/              # 自建后端（Node.js，无框架）
│   ├── index.js         # API：账号 / 动态码 / 下载任务 / 用户管理 / 管理台
│   ├── downloader.js    # 下载器 + ffmpeg 解密 + 合并
│   └── Dockerfile       # 后端镜像
├── third_party/hongguo/ # 红果内容接入层（Kotlin，含 CENC 解密 / 分享链接解析）
└── docs/                # 部署与交接文档
```

## 快速开始（后端，Docker）

```bash
mkdir -p /volume1/docker/shortvideo && cd /volume1/docker/shortvideo
cat > docker-compose.yaml <<'EOF'
services:
  chengzi-backend:
    image: gybeyond/chengzi-backend:latest
    container_name: chengzi-backend
    restart: unless-stopped
    ports:
      - "8888:8080"
    environment:
      - ADMIN_PASSWORD=change-me-admin-pass   # WebUI 管理台密码
      - JWT_SECRET=change-me-jwt-secret       # Token 签名密钥
    volumes:
      - ./chengzi-data:/data    # 用户 / 任务等数据
      - ./chengzi-video:/video  # NAS 下载合并产物目录（固定挂载）
EOF
docker compose up -d
```

- 国内网络拉不动 Docker Hub 时，可用 crane 拉取 `gybeyond/chengzi-backend:latest` 导出 tar，`docker load` 后 `docker compose up -d`。
- 后端健康检查：`curl http://<服务器>:8888/api/health` → `{"status":"ok"}`。

## APK 使用

1. 安装 `app-release.apk`（或自行构建）。
2. 首次打开进入登录 / 注册页，**填写你自己的后端地址**（如 `https://your-domain:1314` 或 `http://192.168.x.x:8888`）。
3. 公开模式直接看红果；设置 → 进入私密模式需输入后端下发的动态码（管理员免输）。
4. 设置 → 黄果网络代理：填 `host:port`（如 `127.0.0.1:7890`），仅黄果走代理，代理不通自动后端绕行。
5. 详情页 → 下载：选择集数 → 下载到手机（本地，合并优先）或下载到 NAS（管理员）。

## 构建 APK

```bash
export ANDROID_HOME=/path/to/android-sdk
export CHENGZI_KEYSTORE=/path/to/your-release.keystore
export CHENGZI_STORE_PASS=your-store-pass
export CHENGZI_KEY_PASS=your-key-pass
export CHENGZI_KEY_ALIAS=your-alias
cd app && ./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

## WebUI 管理台

后端启动后访问 `http://<服务器>:8888/`，输入环境变量 `ADMIN_PASSWORD` 设置的管理员密码进入。

- 用户管理：查看注册用户（用户名 / 密码明文 / 手机型号等）、切换角色（普通 / 管理员）、删除用户。
- 注册开关：可随时开 / 关开放注册（关闭后新用户注册返回"注册已关闭"）。

## 开源协议

本项目遵循 **GPL-3.0** 协议开源（见 [LICENSE](./LICENSE)）。第三方组件与内容源的协议以各自声明为准。

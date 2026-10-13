# Veyra

Veyra 是一个**短剧聚合播放器**：Android 客户端（APK）+ 自建 Node 后端（Docker），支持红果 / 黄果短剧的在线播放、私密模式鉴权、代理绕行，以及**手机端 / NAS 端下载合并**。

> 本项目脱胎于 [MrSsg/HuangguoAPP-for-Andriod](https://github.com/MrSsg/HuangguoAPP-for-Andriod)（GPL-3.0），但已独立演进为 **Veyra** 品牌：独立包名、独立后端镜像、独立数据目录。内容版权归各内容源所有，本项目仅提供技术实现，请勿用于非法用途。

---


## 功能特性

- **多源播放**：红果 / 黄果短剧在线播放；后端地址由用户在登录 / 注册页自填，**不内置任何私有域名**。
- **账号体系**：登录 / 注册（注册开关在 WebUI 动态控制），账号与设备无关。
- **私密模式**：需后端动态码（OTP，鉴权一次、跟账号走）；管理员账号免动态码直进。
- **代理绕行**：设置中可配置代理端口（仅黄果生效），代理不通自动经后端转发，红果不受影响。
- **角色权限**：普通用户只能下载到手机；管理员可下载到 NAS。
- **下载与合并**：
  - 手机端：并发下载（10 路）→ 本地规范化 + 合并为单文件；合并失败自动降级保留分集，可重试 / 重新合并。
  - NAS 端：后端 ffmpeg 下载、解密并合并（avc3/hev1 + 时间基统一），产物直接可播。
  - 手机端合并引擎为**自研 NDK 移植 ffmpeg + 手写 JNI**（C 层解密 / 规范化 / concat），与后端下载器 `server/downloader.js` 语义 1:1 对齐。
- **WebUI 管理台**：用户列表、角色切换、删除用户、注册开关——进入需管理员密码（`ADMIN_PASSWORD`）。

## 项目结构

```
├── app/                      # Android 客户端（Java + WebView UI）
│   └── src/main/
│       ├── java/com/veyra/gybeyond/   # 主程序（播放 / 下载 / 合并 / 账号 / 代理 / 热更新）
│       ├── cpp/ffconcat_jni.c         # 自研合并引擎 JNI（解密 / 规范化 / 统一音轨转码 / concat）
│       ├── jniLibs/                   # 预编译 libffconcat.so（arm64-v8a）
│       └── assets/                    # WebView 前端（app.js / html / css）
├── server/                   # 自建后端（Node.js，无框架）
│   ├── index.js              # API：账号 / 动态码 / 下载任务 / 用户管理 / 管理台
│   ├── downloader.js         # 下载器 + ffmpeg 解密 + 合并（手机端合并的对照基准）
│   ├── hongguo-decrypt.js    # 红果 CENC 解密
│   └── Dockerfile            # 后端镜像
├── third_party/hongguo/      # 红果内容接入层（Kotlin，含分享链接解析）
├── docker-compose.yml        # 后端一键部署
└── docs/                     # 部署与交接文档
```

## 部署后端（Docker Compose）

**Synology NAS / 任意 Docker 主机**，用 Docker Compose 即可：

```bash
services:
  veyra-backend:
    image: gybeyond/veyra-backend:latest
    container_name: veyra
    restart: unless-stopped
    ports:
      - "8888:8888"
    environment:
      - PORT=8888
      - DATA_DIR=/data
      - ADMIN_PASSWORD=change-me   # WebUI 管理台密码（务必修改）
    volumes:
      - ./data:/data            # 用户 / 任务 / 头像等数据（备份此目录）
      - ./video:/video          # NAS 下载合并产物（固定 /video 挂载）
EOF


```

- 端口默认 `8888`（可用 `PORT` 改，APK 登录时填 `http://<NAS-IP>:8888`）。
- **Docker Hub 拉不动**（国内网络）时的离线方案：在 GitHub Release 页下载 `veyra-backend.tar` → 拷到 NAS → `docker load -i veyra-backend.tar` → `docker compose up -d`。
- 健康检查：`curl http://<NAS-IP>:8888/api/health` → `{"status":"ok"}`。
- 升级：`docker compose pull && docker compose up -d`。

## APK 使用

1. 安装 `app-release.apk`（GitHub Releases 或自行构建）。
2. 首次打开进入登录 / 注册页，**填写你自己的后端地址**（如 `http://192.168.x.x:8888`）。
3. 公开模式直接看红果；设置 → 进入私密模式需输入后端动态码（管理员免输）。
4. 设置 → 黄果网络代理：填 `host:port`（仅黄果走代理，代理不通自动后端绕行）。
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

> 手机合并引擎是 NDK C 源码（`app/src/main/cpp/ffconcat_jni.c`），仓库已带预编译 `libffconcat.so`（arm64-v8a），无需本地 ffmpeg 工具链即可构建。

## WebUI 管理台

后端启动后访问 `http://<服务器>:8080/`，输入环境变量 `ADMIN_PASSWORD` 设置的管理员密码进入。

- 用户管理：查看注册用户、切换角色（普通 / 管理员）、删除用户。
- 注册开关：随时开 / 关开放注册。

## 开源协议

本项目遵循 **GPL-3.0** 协议开源（见 [LICENSE](./LICENSE)）。第三方组件与内容源的协议以各自声明为准。

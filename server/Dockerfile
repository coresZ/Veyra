# Veyra 轻量后端镜像
FROM node:22-alpine

# 下载/合并依赖：ffmpeg（concat 合并 + ffprobe 编码探测），alpine 官方包含两件套；
# curl：proxy-fetch.js 抓取底层（黄果站点对 Node TLS 指纹风控，curl 正常）
RUN apk add --no-cache ffmpeg curl

WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev --no-audit --no-fund

COPY index.js ./
COPY webui.html ./
COPY downloader.js ./
COPY hongguo-app.js ./
COPY hongguo-decrypt.js ./
COPY proxy-fetch.js ./

# Go 签名器（红果直连取流，全清晰度 1080p）
COPY hongguo-signer/hongguo-signer ./hongguo-signer/hongguo-signer
RUN chmod +x ./hongguo-signer/hongguo-signer

# 启动脚本：先起 Go 签名器，再起 Node
COPY start.sh ./
RUN chmod +x start.sh

ENV PORT=8080
EXPOSE 8080
VOLUME /data

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/api/health || exit 1

CMD ["./start.sh"]

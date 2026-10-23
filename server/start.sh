#!/bin/sh
# 启动 Go 签名器（红果直连取流），后台运行
/app/hongguo-signer/hongguo-signer &
SIGNER_PID=$!
echo "[start] hongguo-signer pid=$SIGNER_PID"
# 启动 Node 后端
exec node index.js

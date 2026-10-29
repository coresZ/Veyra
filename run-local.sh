#!/usr/bin/env sh
# 本地启动（非容器），读取 config.env；不启动红果签名器
cd "$(dirname "$0")"
exec node start.js
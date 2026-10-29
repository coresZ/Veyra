#!/usr/bin/env node
'use strict';

const { spawn, spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

// ===== 可按需修改的常量 =====
const ROOT = __dirname;
const SERVER_DIR = path.join(ROOT, 'server');
const ENTRY = path.join(SERVER_DIR, 'index.js');
const IS_WIN = process.platform === 'win32';
const NPM = IS_WIN ? 'npm.cmd' : 'npm';
const DEFAULT_CONFIG = path.join(ROOT, 'config.env');

const log = (m) => console.log(`[start] ${m}`);
const warn = (m) => console.warn(`[start] 警告：${m}`);
const die = (m) => { console.error(`[start] 错误：${m}`); process.exit(1); };

// ===== 1. 定位配置文件 =====
// 优先级：--config 参数 > CONFIG_FILE 环境变量 > 默认 config.env
function resolveConfigPath() {
  const i = process.argv.indexOf('--config');
  if (i !== -1 && process.argv[i + 1]) {
    return path.resolve(process.cwd(), process.argv[i + 1]);
  }
  if (process.env.CONFIG_FILE) {
    return path.resolve(process.cwd(), process.env.CONFIG_FILE);
  }
  return DEFAULT_CONFIG;
}

// ===== 2. 解析 KEY=VALUE 配置文件（零依赖）=====
function parseEnvFile(file) {
  const out = {};
  if (!fs.existsSync(file)) return out;
  let text = fs.readFileSync(file, 'utf8');
  text = text.replace(/^\uFEFF/, '');           // 去 BOM
  const lines = text.split(/\r?\n/);
  for (let line of lines) {
    line = line.trim();
    if (!line || line.startsWith('#')) continue;
    if (line.startsWith('export ')) line = line.slice(7).trim();
    line = line.replace(/＝/g, '=');            // 全角等号转半角
    const eq = line.indexOf('=');
    if (eq <= 0) continue;
    const key = line.slice(0, eq).trim();
    let val = line.slice(eq + 1).trim();
    // key 必须是合法变量名，否则跳过（防止整行被误当配置）
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) {
      console.warn(`[start] 警告：忽略无法解析的配置行：${line}`);
      continue;
    }
    if (
      (val.startsWith('"') && val.endsWith('"')) ||
      (val.startsWith("'") && val.endsWith("'"))
    ) {
      val = val.slice(1, -1);
    }
    out[key] = val;
  }
  return out;
}

// ===== 3. 合并配置：真实环境变量覆盖配置文件 =====
const configPath = resolveConfigPath();
const fileCfg = parseEnvFile(configPath);
if (fs.existsSync(configPath)) {
  log(`已读取配置文件：${configPath}`);
} else {
  log(`未找到配置文件（${configPath}），使用环境变量与默认值`);
}
const env = { ...fileCfg, ...process.env };

// ===== 4. 解析关键参数 =====
const PORT = String(env.PORT || '18555');

let DATA_DIR = env.DATA_DIR || path.join(ROOT, 'data');
if (DATA_DIR.includes('=') || DATA_DIR.includes('"') || DATA_DIR.includes("'")) {
  die(`DATA_DIR 配置格式疑似有误：${DATA_DIR}\n请检查 config.env 中 DATA_DIR 那一行（用半角等号、正斜杠，例如 DATA_DIR=D:/data/veyra）`);
}
if (!path.isAbsolute(DATA_DIR)) DATA_DIR = path.resolve(ROOT, DATA_DIR);
let adminPassword = env.ADMIN_PASSWORD;
let generated = false;
if (!adminPassword) {
  adminPassword = crypto.randomBytes(9).toString('hex');
  generated = true;
}

// ===== 5. 环境检查 =====
log(`Node.js ${process.version}`);
if (!fs.existsSync(SERVER_DIR)) die(`找不到后端目录：${SERVER_DIR}`);
if (!fs.existsSync(ENTRY)) die(`找不到入口文件：${ENTRY}（如入口名不同，请修改 ENTRY）`);

const ff = spawnSync('ffmpeg', ['-version'], { stdio: 'ignore' });
if (ff.error || ff.status !== 0) {
  warn('未检测到 ffmpeg，视频下载/合并功能可能不可用。');
}

// ===== 6. 安装依赖 =====
const nodeModules = path.join(SERVER_DIR, 'node_modules');
if (!fs.existsSync(nodeModules)) {
  log('未找到 node_modules，正在执行 npm install ...');
  const r = spawnSync(NPM, ['install'], {
    cwd: SERVER_DIR,
    stdio: 'inherit',
    shell: IS_WIN,
  });
  if (r.status !== 0) die('npm install 失败');
} else {
  log('依赖已存在，跳过 npm install');
}

// ===== 7. 数据目录 =====
try {
  fs.mkdirSync(DATA_DIR, { recursive: true });
} catch (e) {
  die(`无法创建数据目录 ${DATA_DIR}：${e.message}（请检查盘符是否存在、是否有写权限）`);
}
// ===== 8. 启动后端 =====
log(`端口：${PORT}`);
log(`数据目录：${DATA_DIR}`);
log(`管理密码：${adminPassword}${generated ? '（本次随机生成，可在 config.env 中固定）' : ''}`);
log(`健康检查：http://localhost:${PORT}/api/health`);

const child = spawn(process.execPath, ['index.js'], {
  cwd: SERVER_DIR,
  env: { ...env, PORT, DATA_DIR, ADMIN_PASSWORD: adminPassword },
  stdio: 'inherit',
});

['SIGINT', 'SIGTERM'].forEach((sig) =>
  process.on(sig, () => { if (!child.killed) child.kill(sig); })
);
child.on('exit', (code, signal) => {
  log(`后端已退出（code=${code}, signal=${signal}）`);
  process.exit(code ?? 0);
});
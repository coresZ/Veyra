#!/usr/bin/env python3
"""Build and sign a hot-update package for the ChengZi app (ECDSA P-256, SHA256withECDSA).

The APK verifies every package against the public key embedded in
app/src/main/assets/hot-update-public.pem. You hold the matching private key
(chengzi-hotupdate-private.pem, keep it safe and NEVER commit it).

Usage:
  python3 scripts/sign-hot-update.py \\
    --private-key chengzi-hotupdate-private.pem \\
    --revision 1 --version 2026.01.01.1 --min-version-code 42 \\
    --site app/src/main/assets/site-default.json \\
    --theme theme.json --ui-dir ui --out dist/r1

Outputs (both must be uploaded to a GitHub Release named "resources-r<revision>"):
  dist/r1/hot-update.json   -> the envelope (index file, attach as hot-update.json)
  dist/r1/resources-r1.zip  -> the archive

Release URL convention (HotUpdateManager.INDEX):
  https://github.com/<owner>/HuangguoAPP-HotUpdates/releases/latest/download/hot-update.json
"""
import argparse
import base64
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

ALLOWED_EXT = (".html", ".css", ".js", ".svg", ".png", ".jpg", ".jpeg", ".webp",
               ".gif", ".json", ".woff", ".woff2", ".txt")


def collect_zip(site: Path, theme: Path, ui_dir: Path | None, out_zip: Path) -> None:
    out_zip.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.write(site, "site.json")
        zf.write(theme, "theme.json")
        if ui_dir is not None and ui_dir.is_dir():
            for path in sorted(ui_dir.rglob("*")):
                if path.is_file():
                    name = "ui/" + path.relative_to(ui_dir).as_posix()
                    if not name.endswith(ALLOWED_EXT):
                        raise SystemExit(f"拒绝文件（扩展名不在白名单）: {name}")
                    zf.write(path, name)


def sign_payload(payload_bytes: bytes, private_key: Path) -> bytes:
    with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as tmp:
        tmp.write(payload_bytes)
        tmp_path = tmp.name
    try:
        sig = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", str(private_key), tmp_path],
            check=True, capture_output=True).stdout
    finally:
        os.unlink(tmp_path)
    return sig


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--private-key", required=True)
    ap.add_argument("--owner", required=True, help="GitHub 用户名，用于拼接 zip 下载 URL")
    ap.add_argument("--revision", required=True, type=int)
    ap.add_argument("--version", required=True)
    ap.add_argument("--min-version-code", required=True, type=int)
    ap.add_argument("--max-version-code", type=int, default=2 ** 31 - 1)
    ap.add_argument("--site", required=True)
    ap.add_argument("--theme", required=True)
    ap.add_argument("--ui-dir")
    ap.add_argument("--out", required=True)
    ap.add_argument("--notes", default="")
    args = ap.parse_args()

    zip_path = Path(args.out) / f"resources-r{args.revision}.zip"
    collect_zip(Path(args.site), Path(args.theme),
                Path(args.ui_dir) if args.ui_dir else None, zip_path)

    size = zip_path.stat().st_size
    if not (0 < size <= 12 * 1024 * 1024):
        raise SystemExit("包大小必须 0 < size <= 12MB")
    sha256 = hashlib.sha256(zip_path.read_bytes()).hexdigest()

    url = (f"https://github.com/{args.owner}/HuangguoAPP-HotUpdates/releases/download/"
           f"resources-r{args.revision}/resources-r{args.revision}.zip")
    payload = {
        "bridgeVersion": 1,
        "contentModeVersion": 1,
        "minVersionCode": args.min_version_code,
        "maxVersionCode": args.max_version_code,
        "notes": args.notes,
        "revision": args.revision,
        "schema": 1,
        "sha256": sha256,
        "size": size,
        "url": url,
        "version": args.version,
    }
    payload_bytes = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    signature = sign_payload(payload_bytes, Path(args.private_key))

    envelope = {
        "payload": base64.b64encode(payload_bytes).decode("ascii"),
        "signature": base64.b64encode(signature).decode("ascii"),
    }
    envelope_path = Path(args.out) / "hot-update.json"
    envelope_path.write_text(json.dumps(envelope, separators=(",", ":")), encoding="utf-8")
    print(f"已生成: {envelope_path}  (revision {args.revision}, {size} bytes)")
    print(f"已生成: {zip_path}")
    print("发布: 把 hot-update.json 与 zip 作为名为 resources-r<revision> 的 GitHub Release 资产上传即可。")


if __name__ == "__main__":
    main()

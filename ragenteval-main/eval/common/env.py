"""RAGent 连接参数（地址 / 账号）的统一读取入口。

优先级：进程环境变量 > 项目根目录 `.env` > 代码默认值。

`.env` 由本模块自己解析（只认 `KEY=VALUE`，不引第三方依赖）。评测机上的
RAGent 地址和账号因环境而异，把三个变量写进 `.env` 就能免去每次 `export`；
`.env` 已在 `.gitignore` 里，不会进版本库。
"""
from __future__ import annotations

import os
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
ENV_FILE = PROJECT_ROOT / ".env"

DEFAULT_RAGENT_BASE_URL = "http://localhost:9090/api/ragent"

_CREDENTIAL_HINT = (
    "缺少 RAGent 登录账号，任选一种方式配置：\n"
    f"  1. 在 {ENV_FILE} 里写 RAGENT_USERNAME / RAGENT_PASSWORD（模板见 .env.example）\n"
    "  2. export RAGENT_USERNAME=<用户名> RAGENT_PASSWORD=<密码>\n"
    "账号需在 RAGent 服务端已存在，且服务端开启 app.eval.enabled=true。"
)


def load_env_file(path: Path | None = None, *, override: bool = False) -> dict[str, str]:
    """读取 .env 并写入 os.environ。

    Args:
        path: .env 路径，默认项目根目录下的 .env。
        override: 为 True 时覆盖已有的同名环境变量；默认不覆盖，让 export 优先。

    Returns:
        文件里读到的键值对；文件不存在时返回空字典。
    """
    env_path = Path(path) if path is not None else ENV_FILE
    if not env_path.exists():
        return {}

    values: dict[str, str] = {}
    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export ") :].strip()
        key, sep, value = line.partition("=")
        key = key.strip()
        if not sep or not key:
            continue
        value = value.strip().strip('"').strip("'")
        if override or not os.environ.get(key):
            os.environ[key] = value
        values[key] = value
    return values


def _config(name: str) -> str | None:
    """按 环境变量 → .env 的顺序取一个配置项。"""
    return os.environ.get(name) or load_env_file().get(name) or None


def ragent_base_url() -> str:
    """RAGent 服务根地址，去掉结尾斜杠。"""
    return (_config("RAGENT_BASE_URL") or DEFAULT_RAGENT_BASE_URL).rstrip("/")


def ragent_credentials() -> tuple[str, str]:
    """RAGent 登录账号密码。

    Returns:
        (username, password)

    Raises:
        RuntimeError: 用户名或密码缺失，message 里带配置指引。
    """
    username = _config("RAGENT_USERNAME")
    password = _config("RAGENT_PASSWORD")
    if not username or not password:
        raise RuntimeError(_CREDENTIAL_HINT)
    return username, password

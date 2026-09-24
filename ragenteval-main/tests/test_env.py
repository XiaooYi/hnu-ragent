"""eval.common.env 的单测：环境变量优先、.env 兜底、缺账号时的报错。

跑法（不需要第三方依赖）：

    python3 -m unittest discover -s tests
"""
from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from eval.common import env


class RagentEnvTest(unittest.TestCase):
    """覆盖 .env 解析、优先级和默认值。"""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.env_path = Path(self._tmp.name) / ".env"
        patcher = mock.patch.object(env, "ENV_FILE", self.env_path)
        patcher.start()
        self.addCleanup(patcher.stop)
        # 隔离真实进程环境，避免本机已有的 RAGENT_* 干扰断言
        patcher_env = mock.patch.dict(os.environ, {}, clear=False)
        patcher_env.start()
        self.addCleanup(patcher_env.stop)
        for name in ("RAGENT_BASE_URL", "RAGENT_USERNAME", "RAGENT_PASSWORD"):
            os.environ.pop(name, None)

    def _write_env(self, body: str) -> None:
        self.env_path.write_text(body, encoding="utf-8")

    def test_base_url_default(self) -> None:
        """未配置任何来源时回落到默认地址。"""
        self.assertEqual(env.ragent_base_url(), env.DEFAULT_RAGENT_BASE_URL)

    def test_base_url_from_env_file(self) -> None:
        """.env 里的地址生效，且去掉结尾斜杠。"""
        self._write_env("# 注释\nRAGENT_BASE_URL=http://localhost/api/ragent/\n")
        self.assertEqual(env.ragent_base_url(), "http://localhost/api/ragent")

    def test_process_env_wins(self) -> None:
        """同名项以进程环境变量为准，.env 只补空缺。"""
        self._write_env("RAGENT_USERNAME=from_file\nRAGENT_PASSWORD=from_file_pw\n")
        os.environ["RAGENT_USERNAME"] = "from_shell"
        self.assertEqual(env.ragent_credentials(), ("from_shell", "from_file_pw"))

    def test_env_file_supports_export_and_quotes(self) -> None:
        """.env 容忍 `export` 前缀和成对引号。"""
        self._write_env('export RAGENT_USERNAME="eval_bot"\nRAGENT_PASSWORD=\'pw\'\n')
        self.assertEqual(env.ragent_credentials(), ("eval_bot", "pw"))

    def test_missing_credentials_has_hint(self) -> None:
        """缺账号时抛 RuntimeError，且 message 里带可照做的配置指引。"""
        self._write_env("RAGENT_BASE_URL=http://localhost/api/ragent\n")
        with self.assertRaises(RuntimeError) as ctx:
            env.ragent_credentials()
        self.assertIn("RAGENT_USERNAME", str(ctx.exception))

    def test_override_only_when_asked(self) -> None:
        """默认不覆盖已有环境变量，override=True 才覆盖。"""
        self._write_env("RAGENT_USERNAME=from_file\n")
        os.environ["RAGENT_USERNAME"] = "from_shell"
        env.load_env_file()
        self.assertEqual(os.environ["RAGENT_USERNAME"], "from_shell")
        env.load_env_file(override=True)
        self.assertEqual(os.environ["RAGENT_USERNAME"], "from_file")

    def test_missing_file_returns_empty(self) -> None:
        """.env 不存在时返回空字典，不抛异常。"""
        self.assertEqual(env.load_env_file(), {})


if __name__ == "__main__":
    unittest.main()

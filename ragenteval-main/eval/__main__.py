"""让 `python -m eval ...` 可用。真正的入口在 cli.py。"""
from __future__ import annotations

import sys

from eval.common.cli import main

if __name__ == "__main__":
    sys.exit(main())

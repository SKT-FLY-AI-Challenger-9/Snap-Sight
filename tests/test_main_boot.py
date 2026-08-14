# tests/test_main_boot.py
"""backend.main이 필수 환경변수 없이 부팅하면 실패하는지 서브프로세스로 검증한다."""

import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent


def test_main_fails_to_boot_without_required_env(tmp_path):
    """ANTHROPIC_API_KEY가 없는 환경에서 backend.main을 임포트하면 0이 아닌 종료 코드로 실패한다."""
    env = {"PATH": os.environ.get("PATH", ""), "PYTHONPATH": str(REPO_ROOT)}
    result = subprocess.run(
        [sys.executable, "-c", "import backend.main"],
        cwd=tmp_path,
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode != 0
    assert "ANTHROPIC_API_KEY" in result.stderr

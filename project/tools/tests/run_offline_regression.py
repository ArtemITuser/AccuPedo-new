from __future__ import annotations
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[2]
commands = [
    [sys.executable, str(root / "tools/tests/accupedo_csv_regression.py")],
    [sys.executable, str(root / "tools/tests/accupedo_db_regression.py")],
    [sys.executable, str(root / "tools/tests/backup_format_regression.py")],
    [sys.executable, str(root / "tools/static-build-audit.py")],
]
for command in commands:
    subprocess.run(command, check=True, cwd=root)

kotlinc = subprocess.run(["which", "kotlinc"], capture_output=True, text=True).stdout.strip()
if kotlinc:
    jar = root / "build" / "offline-pure-tests.jar"
    jar.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([kotlinc,
        str(root / "app/src/main/java/com/stepper/app/data/StepMath.kt"),
        str(root / "app/src/main/java/com/stepper/app/location/LocationMath.kt"),
        str(root / "tools/pure-test-main.kt"), "-include-runtime", "-d", str(jar)], check=True, cwd=root)
    subprocess.run(["java", "-jar", str(jar)], check=True, cwd=root)

print("OFFLINE REGRESSION SUITE: PASS")

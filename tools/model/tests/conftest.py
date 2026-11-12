import sys
from pathlib import Path

# The scripts of tools/model import each other by module name, as when they are run directly.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

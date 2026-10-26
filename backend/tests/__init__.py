"""Tests del backend: python3 -m unittest discover -s backend/tests"""

import sys
from pathlib import Path

# Los módulos del daemon viven como archivos sueltos en backend/bin (así se
# instalan en /usr/local/bin): se importan agregando esa carpeta al path.
BIN = Path(__file__).resolve().parent.parent / "bin"
if str(BIN) not in sys.path:
    sys.path.insert(0, str(BIN))

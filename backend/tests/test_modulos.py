"""El daemon dividido en panda_*.py importa y expone todo lo que usa main()."""

import ast
import importlib
import importlib.util
import unittest

from tests import BIN

MODULOS = ["panda_core", "panda_metrics", "panda_media", "panda_screens",
           "panda_system", "panda_power"]


def cargar_daemon():
    spec = importlib.util.spec_from_file_location(
        "apppanda_backend", BIN / "apppanda-backend.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class ModulosTest(unittest.TestCase):
    def test_modulos_importan(self):
        for m in MODULOS:
            with self.subTest(modulo=m):
                importlib.import_module(m)

    def test_daemon_importa(self):
        cargar_daemon()

    def test_api_de_main_resuelve(self):
        """Cada valor del SimpleNamespace `api` de main() existe en el daemon."""
        daemon = cargar_daemon()
        tree = ast.parse((BIN / "apppanda-backend.py").read_text())
        nombres = set()
        for node in ast.walk(tree):
            if (isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
                    and node.func.id == "SimpleNamespace"):
                for kw in node.keywords:
                    if isinstance(kw.value, ast.Name):
                        nombres.add(kw.value.id)
        self.assertGreater(len(nombres), 50)
        faltan = sorted(n for n in nombres if n not in ("ctx", "run") and not hasattr(daemon, n))
        self.assertEqual(faltan, [])

    def test_sin_definiciones_duplicadas(self):
        vistos = {}
        for f in ["apppanda-backend.py", *(m + ".py" for m in MODULOS)]:
            for node in ast.parse((BIN / f).read_text()).body:
                if isinstance(node, (ast.FunctionDef, ast.ClassDef)):
                    with self.subTest(nombre=node.name):
                        self.assertNotIn(node.name, vistos, f"{f} y {vistos.get(node.name)}")
                    vistos[node.name] = f


if __name__ == "__main__":
    unittest.main()

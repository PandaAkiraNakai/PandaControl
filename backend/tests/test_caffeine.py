"""Caffeine: temporizador, persistencia en disco y restauración al arrancar.

Sin tocar el escritorio: se reemplazan las funciones que hablan con
Noctalia/logind por dobles que registran las llamadas.
"""

import json
import tempfile
import time
import unittest
from pathlib import Path

import panda_power as pp


class CaffeineTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.state_dir = Path(self.tmp.name)
        self.activo = False
        self.llamadas = []

        def apply(on, backend):
            self.llamadas.append(on)
            self.activo = on
            return "ok"

        self._orig = (pp._caffeine_apply, pp._caffeine_backend, pp._caffeine_active)
        pp._caffeine_apply = apply
        pp._caffeine_backend = lambda: "systemd"
        pp._caffeine_active = lambda backend: self.activo
        pp._CAFFEINE_STATE = None
        pp._caffeine_cancel_timer()
        pp._CAFFEINE_STATE = self.state_dir / "caffeine.json"

    def tearDown(self):
        pp._caffeine_cancel_timer()
        pp._CAFFEINE_STATE = None
        pp._caffeine_apply, pp._caffeine_backend, pp._caffeine_active = self._orig
        self.tmp.cleanup()

    @property
    def archivo(self):
        return self.state_dir / "caffeine.json"

    def test_minutos_fuera_de_rango(self):
        self.assertIn("fuera de rango", pp.caffeine_set(True, 0))
        self.assertIn("fuera de rango", pp.caffeine_set(True, 24 * 60 + 1))
        self.assertEqual(self.llamadas, [])

    def test_sin_limite_no_guarda_temporizador(self):
        self.assertEqual(pp.caffeine_set(True), "ok")
        st = pp.caffeine_state()
        self.assertTrue(st["active"])
        self.assertIsNone(st["until"])
        self.assertFalse(self.archivo.exists())

    def test_con_minutos_guarda_y_reporta_restante(self):
        pp.caffeine_set(True, 30)
        st = pp.caffeine_state()
        self.assertTrue(st["active"])
        self.assertTrue(29 * 60 < st["remaining_s"] <= 30 * 60)
        guardado = json.loads(self.archivo.read_text())["until"]
        self.assertAlmostEqual(guardado, st["until"], delta=1)

    def test_apagar_borra_el_archivo(self):
        pp.caffeine_set(True, 30)
        pp.caffeine_set(False)
        self.assertFalse(self.archivo.exists())
        self.assertIsNone(pp.caffeine_state()["until"])

    def test_apagado_desde_el_pc_cancela_el_temporizador(self):
        pp.caffeine_set(True, 30)
        self.activo = False  # lo apagaron desde la barra de Noctalia
        self.assertIsNone(pp.caffeine_state()["until"])
        self.assertFalse(self.archivo.exists())

    def test_vence_y_apaga(self):
        pp._caffeine_schedule(time.time() + 0.2)
        self.activo = True
        time.sleep(0.6)
        self.assertEqual(self.llamadas[-1], False)
        self.assertFalse(self.archivo.exists())

    def test_restore_retoma_el_temporizador(self):
        until = time.time() + 600
        self.archivo.write_text(json.dumps({"until": until}))
        self.activo = True
        pp.caffeine_restore(self.state_dir)
        st = pp.caffeine_state()
        self.assertEqual(st["until"], int(until))
        self.assertEqual(self.llamadas, [])  # no toca el estado actual

    def test_restore_vencido_apaga(self):
        self.archivo.write_text(json.dumps({"until": time.time() - 5}))
        self.activo = True
        pp.caffeine_restore(self.state_dir)
        self.assertEqual(self.llamadas, [False])
        self.assertFalse(self.archivo.exists())

    def test_restore_ignora_archivo_corrupto(self):
        self.archivo.write_text("no es json")
        pp.caffeine_restore(self.state_dir)
        self.assertEqual(self.llamadas, [])


if __name__ == "__main__":
    unittest.main()

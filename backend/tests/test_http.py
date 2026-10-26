"""Servidor HTTP real (puerto efímero en 127.0.0.1) con una API falsa."""

import json
import unittest
import urllib.error
import urllib.request
from types import SimpleNamespace

from http_server import EventBroker, start_http_server

TOKEN = "token-de-prueba"
PNG = b"\x89PNG\r\n\x1a\nfalso"


class HttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.caffeine = {"active": False, "backend": "systemd", "until": None, "remaining_s": None}
        cls.llamadas = []

        def caffeine_set(on, minutes=None):
            cls.llamadas.append((on, minutes))
            cls.caffeine["active"] = on
            return "ok"

        cfg = {"apps": {
            "steam": {"label": "Steam", "cmd": ["uwsm", "app", "--", "steam.desktop"]},
            "sinicono": {"label": "Sin ícono", "cmd": ["true"]},
        }}
        api = SimpleNamespace(
            ctx=SimpleNamespace(cfg=cfg),
            caffeine_state=lambda: dict(cls.caffeine),
            caffeine_set=caffeine_set,
            app_icon_png=lambda name, _cfg: PNG if name == "steam" else None,
        )
        cls.server = start_http_server(api, EventBroker(), host="127.0.0.1", port=0,
                                       tokens=[TOKEN])
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        self.llamadas.clear()

    def pedir(self, path, body=None, token=TOKEN):
        req = urllib.request.Request(self.base + path, method="POST" if body is not None else "GET",
                                     data=json.dumps(body).encode() if body is not None else None)
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        if body is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, r.headers.get("Content-Type", ""), r.read()
        except urllib.error.HTTPError as e:
            with e:
                return e.code, e.headers.get("Content-Type", ""), e.read()

    def test_health_es_publico(self):
        status, _, body = self.pedir("/api/v1/health", token=None)
        self.assertEqual(status, 200)
        self.assertTrue(json.loads(body)["ok"])

    def test_sin_token_401(self):
        self.assertEqual(self.pedir("/api/v1/caffeine", token=None)[0], 401)
        self.assertEqual(self.pedir("/api/v1/caffeine", token="malo")[0], 401)

    def test_ruta_inexistente_404(self):
        self.assertEqual(self.pedir("/api/v1/no-existe")[0], 404)

    def test_caffeine_estado(self):
        status, _, body = self.pedir("/api/v1/caffeine")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["backend"], "systemd")

    def test_caffeine_on_con_minutos(self):
        status, _, body = self.pedir("/api/v1/caffeine/on", {"minutes": 30})
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["result"], "ok")
        self.assertEqual(self.llamadas, [(True, 30)])

    def test_caffeine_off_sin_cuerpo(self):
        status, _, _ = self.pedir("/api/v1/caffeine/off", {})
        self.assertEqual(status, 200)
        self.assertEqual(self.llamadas, [(False, None)])

    def test_caffeine_valida_minutos(self):
        for malo in ("30", 1.5, True):
            with self.subTest(minutes=malo):
                self.assertEqual(self.pedir("/api/v1/caffeine/on", {"minutes": malo})[0], 400)
        self.assertEqual(self.pedir("/api/v1/caffeine/encender", {})[0], 400)
        self.assertEqual(self.llamadas, [])

    def test_apps_indica_icono(self):
        status, _, body = self.pedir("/api/v1/apps")
        self.assertEqual(status, 200)
        apps = {a["name"]: a for a in json.loads(body)["apps"]}
        self.assertTrue(apps["steam"]["icon"])
        self.assertFalse(apps["sinicono"]["icon"])

    def test_icono_png(self):
        status, ctype, body = self.pedir("/api/v1/apps/steam/icon")
        self.assertEqual((status, ctype, body), (200, "image/png", PNG))
        self.assertEqual(self.pedir("/api/v1/apps/sinicono/icon")[0], 404)
        self.assertEqual(self.pedir("/api/v1/apps/steam/icon", token=None)[0], 401)


if __name__ == "__main__":
    unittest.main()

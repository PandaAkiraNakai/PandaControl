"""Control de mouse y teclado del PC desde Panda Control.

Mouse: socket DGRAM persistente a ydotoold (escribe `struct input_event`
  directo, sin fork/exec por movimiento → soporta alta frecuencia y se
  siente fluido). Fallback al binario `ydotool` si el socket no está.
    sudo pacman -S ydotool
    systemctl --user enable --now ydotool.service

Teclado/texto: wtype (no necesita daemon). En compositores sin
  zwp_virtual_keyboard_v1 (KDE Plasma) cae a ydotoold, traduciendo el texto con
  la distribución de teclado de la sesión (libxkbcommon).
"""

from __future__ import annotations

import glob
import os
import re
import socket
import struct
import subprocess
import sys
import threading
import time


# ─── Entorno ──────────────────────────────────────────────────────────────────

def _env() -> dict:
    """Entorno Wayland de la sesión viva: COSMIC usaba wayland-1 y KWin wayland-0,
    así que se toma el socket más reciente en vez de fijar uno."""
    uid = os.getuid()
    runtime_dir = f"/run/user/{uid}"
    socks = sorted(
        (p for p in glob.glob(f"{runtime_dir}/wayland-*") if not p.endswith(".lock")),
        key=os.path.getmtime, reverse=True,
    )
    return {
        **os.environ,
        "XDG_RUNTIME_DIR": runtime_dir,
        "WAYLAND_DISPLAY": os.path.basename(socks[0]) if socks else "wayland-0",
    }


def _ydotool_socket_path() -> str:
    return os.environ.get(
        "YDOTOOL_SOCKET", f"/run/user/{os.getuid()}/.ydotool_socket",
    )


# ─── Códigos de evento (linux/input-event-codes.h) ────────────────────────────

EV_SYN, EV_KEY, EV_REL = 0x00, 0x01, 0x02
SYN_REPORT = 0x00
REL_X, REL_Y, REL_WHEEL, REL_HWHEEL = 0x00, 0x01, 0x08, 0x06
BTN_LEFT, BTN_RIGHT, BTN_MIDDLE = 0x110, 0x111, 0x112
# Botones laterales: el compositor los mapea a botón 8 (atrás) y 9 (adelante),
# que navegadores y gestores de archivos interpretan como retroceder/avanzar.
BTN_SIDE, BTN_EXTRA = 0x113, 0x114

# struct input_event en x86-64: timeval(2x long=16) + type(u16)+code(u16)+value(s32)
_EVENT = struct.Struct("@llHHi")


def _event(etype: int, code: int, value: int) -> bytes:
    return _EVENT.pack(0, 0, etype, code, value)


_SYN = _event(EV_SYN, SYN_REPORT, 0)


# ─── Cliente persistente a ydotoold ───────────────────────────────────────────

class _YdotoolSocket:
    """Mantiene un socket DGRAM abierto a ydotoold y reenvía input_events.
    Reconecta solo si el envío falla. Thread-safe."""

    def __init__(self):
        self._sock: socket.socket | None = None
        self._lock = threading.Lock()

    def _connect(self) -> socket.socket | None:
        try:
            s = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
            s.connect(_ydotool_socket_path())
            return s
        except OSError:
            return None

    def send(self, payload: bytes) -> bool:
        """Envía input_events a ydotoold. CADA evento va en su propio datagrama:
        ydotoold lee un solo `struct input_event` por recv, así que un datagrama
        con varios eventos se trunca y se pierden todos menos el primero (era el
        bug que rompía las diagonales: REL_Y y SYN se descartaban)."""
        chunks = [
            payload[i:i + _EVENT.size]
            for i in range(0, len(payload), _EVENT.size)
        ]
        with self._lock:
            if self._sock is None:
                self._sock = self._connect()
            if self._sock is None:
                return False
            try:
                for c in chunks:
                    self._sock.send(c)
                return True
            except OSError:
                # ydotoold reinició o cerró: reconecta una vez y reintenta.
                try:
                    self._sock.close()
                except OSError:
                    pass
                self._sock = self._connect()
                if self._sock is None:
                    return False
                try:
                    for c in chunks:
                        self._sock.send(c)
                    return True
                except OSError:
                    return False


_YD = _YdotoolSocket()


def _move_payload(dx: int, dy: int) -> bytes:
    payload = b""
    if dx:
        payload += _event(EV_REL, REL_X, dx)
    if dy:
        payload += _event(EV_REL, REL_Y, dy)
    return payload + _SYN


# ─── Interpolador de movimiento (suaviza saltos grandes) ──────────────────────

def _quantize(step: float, total: float) -> int:
    """Entero a aplicar este tick avanzando hacia el objetivo. Si el paso
    suavizado es <1px pero todavía queda ≥1px pendiente, avanza 1px para no
    estancarse; si el pendiente ya es sub-pixel, devuelve 0 (lo conserva)."""
    if step >= 1.0 or step <= -1.0:
        return int(step)  # trunca hacia cero
    if total >= 1.0:
        return 1
    if total <= -1.0:
        return -1
    return 0


class _MouseMover:
    """Recibe deltas objetivo y los aplica al socket en sub-pasos finos a alta
    frecuencia. Así un delta grande (p. ej. 50px de un flick) no se ve como un
    salto: se reparte en ~280 Hz con easing, quedando movimiento continuo.

    En movimiento sostenido el paso por tick ≈ velocidad/FREQ (px), mucho más
    fino que aplicar el delta crudo del cliente cada ~12 ms."""

    # FREQ × MAX_STEP = velocidad máxima de salida (px/s). Debe SUPERAR la
    # velocidad de entrada del dedo (hasta ~16000 px/s en flicks) o el pendiente
    # se acumula y el cursor se arrastra detrás (rompía círculos/diagonales).
    FREQ = 400.0          # Hz del hilo aplicador
    ALPHA = 0.5           # fracción del pendiente que se consume por tick (easing)
    MAX_STEP = 40         # tope de px por tick (por magnitud): suaviza flicks extremos
    IDLE_TIMEOUT_S = 2.0  # sin pendiente durante esto, el hilo se apaga solo

    def __init__(self):
        self._tx = 0.0
        self._ty = 0.0
        self._lock = threading.Lock()
        self._started = False

    def add(self, dx: float, dy: float) -> None:
        with self._lock:
            self._tx += dx
            self._ty += dy
            if not self._started:
                self._started = True
                threading.Thread(
                    target=self._loop, daemon=True, name="mouse-mover",
                ).start()

    def _loop(self) -> None:
        dt = 1.0 / self.FREQ
        idle_ticks = 0
        idle_limit = max(1, int(self.IDLE_TIMEOUT_S * self.FREQ))
        while True:
            with self._lock:
                tx, ty = self._tx, self._ty
                if tx or ty:
                    idle_ticks = 0
                else:
                    idle_ticks += 1
                    if idle_ticks >= idle_limit:
                        # Nada pendiente hace IDLE_TIMEOUT_S: apagarse. El
                        # chequeo y el apagado quedan atómicos bajo el mismo
                        # lock que add() usa para relanzar, así no se pierde
                        # un delta que llegue justo en este instante.
                        self._started = False
                        return
            if tx or ty:
                sx = tx * self.ALPHA
                sy = ty * self.ALPHA
                # Tope por MAGNITUD del vector (no por eje) para no deformar la
                # dirección: en diagonal, escalar ambos ejes por igual mantiene
                # el ángulo. Capear cada eje aparte forzaría 45° y rompía los
                # círculos.
                mag = (sx * sx + sy * sy) ** 0.5
                if mag > self.MAX_STEP:
                    f = self.MAX_STEP / mag
                    sx *= f
                    sy *= f
                ix = _quantize(sx, tx)
                iy = _quantize(sy, ty)
                if ix or iy:
                    if not _YD.send(_move_payload(ix, iy)):
                        _ydotool_cli(["mousemove", "--", str(ix), str(iy)])
                    with self._lock:
                        self._tx -= ix
                        self._ty -= iy
            time.sleep(dt)


_MOVER = _MouseMover()


# ─── Fallback: binario ydotool ────────────────────────────────────────────────

def _ydotool_cli(args: list[str], timeout: int = 3) -> str:
    try:
        proc = subprocess.run(
            ["ydotool", *args],
            capture_output=True, text=True, timeout=timeout, env=_env(),
        )
    except FileNotFoundError:
        return ("ydotool no instalado (sudo pacman -S ydotool && "
                "systemctl --user enable --now ydotool.service)")
    except subprocess.TimeoutExpired:
        return "timeout"
    if proc.returncode != 0:
        return (proc.stderr or proc.stdout or f"exit {proc.returncode}").strip()[:200]
    return "ok"


# ─── Mouse ────────────────────────────────────────────────────────────────────

def mouse_move(dx: int, dy: int) -> str:
    """Encola el delta en el interpolador, que lo aplica suavizado a alta
    frecuencia. No aplica el movimiento de golpe (eso causaba tirones con
    deltas grandes)."""
    dx = max(-5000, min(5000, int(dx)))
    dy = max(-5000, min(5000, int(dy)))
    if dx or dy:
        _MOVER.add(dx, dy)
    return "ok"


_BUTTON_CODES = {
    "left": BTN_LEFT, "right": BTN_RIGHT, "middle": BTN_MIDDLE,
    "back": BTN_SIDE, "forward": BTN_EXTRA,
}
_BUTTON_CLI = {
    "left": "0xC0", "right": "0xC1", "middle": "0xC2",
    "back": "0xC3", "forward": "0xC4",
}


def mouse_move_absolute(x: int, y: int) -> str:
    """Teletransporta el puntero a una coordenada del layout (no pasa por el
    interpolador: es un salto, no un gesto). Lo usa "fijar monitor" en COSMIC,
    donde la pantalla activa es la que tiene el cursor."""
    return _ydotool_cli(["mousemove", "--absolute", "-x", str(int(x)), "-y", str(int(y))])


def mouse_click(button: str) -> str:
    code = _BUTTON_CODES.get(button)
    if code is None:
        return f"botón desconocido: {button}"
    payload = (
        _event(EV_KEY, code, 1) + _SYN
        + _event(EV_KEY, code, 0) + _SYN
    )
    if _YD.send(payload):
        return "ok"
    return _ydotool_cli(["click", _BUTTON_CLI[button]])


_SCROLL_DELTA = {"up": 1, "down": -1}
_SCROLL_CLI = {"up": "0xC4", "down": "0xC5"}


def mouse_scroll(direction: str) -> str:
    delta = _SCROLL_DELTA.get(direction)
    if delta is None:
        return f"dirección desconocida: {direction}"
    payload = _event(EV_REL, REL_WHEEL, delta) + _SYN
    if _YD.send(payload):
        return "ok"
    return _ydotool_cli(["click", _SCROLL_CLI[direction]])


# ─── Resaltar cursor (agrandar temporalmente para ubicarlo) ───────────────────
#
# niri redibuja el cursor en caliente al cambiar `xcursor-size` en su config.
# Para "encontrar el mouse" (p. ej. en la tele, donde el puntero se pierde) lo
# agrandamos unos segundos y lo devolvemos a su tamaño solo. Editamos la línea
# `xcursor-size` en la config de niri sin importar en qué archivo incluido viva.

_NIRI_CONFIG_DIR = os.path.expanduser(
    os.environ.get("NIRI_CONFIG_DIR", "~/.config/niri")
)
_XCURSOR_RE = re.compile(r"(xcursor-size\s+)(\d+)")

_CURSOR_LOCK = threading.Lock()
# gen: cada pulsación incrementa el contador; solo la reversión de la última
# pulsación encoge el cursor (así pulsar de nuevo extiende la ventana en vez de
# encoger antes de tiempo). normal: tamaño "de reposo" recordado entre pulsos.
_CURSOR_STATE = {"gen": 0, "normal": None}


def _find_cursor_cfg() -> tuple[str, int] | None:
    """(ruta, tamaño_actual) del primer .kdl de niri que define xcursor-size."""
    for path in sorted(glob.glob(
        os.path.join(_NIRI_CONFIG_DIR, "**", "*.kdl"), recursive=True,
    )):
        try:
            with open(path, encoding="utf-8") as f:
                text = f.read()
        except OSError:
            continue
        m = _XCURSOR_RE.search(text)
        if m:
            return path, int(m.group(2))
    return None


def _set_cursor_size(path: str, size: int) -> bool:
    """Reescribe xcursor-size en `path`. Atómico (tmp + rename) para que niri
    nunca lea el archivo a medio escribir."""
    try:
        with open(path, encoding="utf-8") as f:
            text = f.read()
    except OSError:
        return False
    new = _XCURSOR_RE.sub(rf"\g<1>{size}", text, count=1)
    if new == text:
        return False
    tmp = path + ".apppanda.tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(new)
        os.replace(tmp, path)
        return True
    except OSError:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        return False


def _cursor_nudge() -> None:
    """Micro-movimiento (1px ida y vuelta) para que niri redibuje el cursor con
    el nuevo tamaño al instante, sin esperar a que lo muevas."""
    _YD.send(_move_payload(1, 0))
    _YD.send(_move_payload(-1, 0))


def cursor_jiggle(pulsos: int = 8, amplitud: int = 70) -> str:
    """Sacude el puntero para ubicarlo a ojo. Es el reemplazo del cursor grande
    en escritorios que no releen el tamaño en caliente: COSMIC toma XCURSOR_SIZE
    al arrancar la sesión, así que no hay ninguna config que editar al vuelo.
    Queda en el mismo lugar (los pulsos se cancelan de ida y vuelta)."""
    for i in range(pulsos):
        _YD.send(_move_payload(amplitud if i % 2 == 0 else -amplitud, 0))
        time.sleep(0.05)
    return "ok"


def cursor_highlight(big: int = 72, seconds: float = 2.0) -> str:
    """Agranda el cursor de niri a `big` px durante `seconds` y lo devuelve a su
    tamaño normal. Pensado para ubicar el puntero en la tele. Si se pulsa de
    nuevo mientras está grande, extiende la ventana en vez de encoger antes."""
    found = _find_cursor_cfg()
    if found is None:
        # Sin config de niri (COSMIC, Plasma) no hay tamaño que cambiar en
        # caliente: se sacude el puntero, que cumple el mismo propósito.
        return cursor_jiggle()
    path, cur = found
    with _CURSOR_LOCK:
        # El tamaño normal es el de reposo: lo capturamos cuando vemos un valor
        # distinto del agrandado y lo recordamos durante la ráfaga de pulsos.
        if cur != big:
            _CURSOR_STATE["normal"] = cur
        normal = _CURSOR_STATE["normal"]
        if normal is None:
            normal = 24  # fallback si arrancamos con el cursor ya grande
        _CURSOR_STATE["gen"] += 1
        my_gen = _CURSOR_STATE["gen"]
    if cur != big and not _set_cursor_size(path, big):
        return "no pude editar la config de niri"
    _cursor_nudge()

    def _revert() -> None:
        time.sleep(seconds)
        with _CURSOR_LOCK:
            if _CURSOR_STATE["gen"] != my_gen:
                return  # una pulsación más nueva se hará cargo
        if _set_cursor_size(path, normal):
            _cursor_nudge()

    threading.Thread(target=_revert, daemon=True, name="cursor-revert").start()
    return "ok"



# ─── Teclado sin zwp_virtual_keyboard_v1 (KDE Plasma) ─────────────────────────
#
# KWin no ofrece el protocolo de teclado virtual a clientes comunes, así que
# wtype falla con "Compositor does not support the virtual keyboard protocol".
# En ese caso las teclas salen por el uinput de ydotoold, que son códigos de
# tecla físicos: el compositor los traduce con SU distribución (p. ej. "es").
# Para escribir texto hace falta el mapa inverso carácter → tecla+modificadores
# de esa distribución; se arma con libxkbcommon (ctypes, sin dependencias).

import ctypes
import ctypes.util
import unicodedata

KEY_LEFTSHIFT, KEY_LEFTCTRL, KEY_LEFTALT, KEY_RIGHTALT, KEY_LEFTMETA = 42, 29, 56, 100, 125

_EVDEV_KEYS: dict[str, int] = {
    "Return": 28, "Escape": 1, "Tab": 15, "BackSpace": 14, "Delete": 111,
    "Up": 103, "Down": 108, "Left": 105, "Right": 106, "Home": 102, "End": 107,
    "Page_Up": 104, "Page_Down": 109, "Print": 99, "super_L": KEY_LEFTMETA,
    "F1": 59, "F2": 60, "F3": 61, "F4": 62, "F5": 63, "F6": 64, "F7": 65,
    "F8": 66, "F9": 67, "F10": 68, "F11": 87, "F12": 88,
}
_EVDEV_MODS = {"ctrl": KEY_LEFTCTRL, "shift": KEY_LEFTSHIFT, "alt": KEY_LEFTALT,
               "logo": KEY_LEFTMETA}
# Teclas muertas (keysyms) para componer vocales con tilde, diéresis, etc.
_DEAD_KEYSYMS = {"́": 0xFE51, "̀": 0xFE50, "̂": 0xFE52,
                 "̃": 0xFE53, "̈": 0xFE57}
# Signos que en distribuciones como "es" solo existen como tecla muerta + espacio.
_SPACING_ACCENTS = {"^": "\u0302", "`": "\u0300", "´": "\u0301", "¨": "\u0308", "~": "\u0303"}
_KEYPAD = {55, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 96, 98, 117, 121}
_UNSUPPORTED_MSG = "does not support the virtual keyboard protocol"


def _peso(combo: tuple[int, list[int]]) -> tuple[int, int, int]:
    """Preferencia entre teclas que dan el mismo carácter: teclado principal antes
    que teclas multimedia (códigos altos) o el numérico, y menos modificadores."""
    code, mods = combo
    return (code > 255, code in _KEYPAD, len(mods))


def _layout_actual() -> tuple[str, str]:
    """Distribución de teclado de la sesión: kxkbrc (KDE), si no la del sistema."""
    try:
        with open(os.path.expanduser("~/.config/kxkbrc")) as f:
            txt = f.read()
        lay = re.search(r"^LayoutList=([^,\n]+)", txt, re.M)
        var = re.search(r"^VariantList=([^,\n]*)", txt, re.M)
        if lay:
            return lay.group(1).strip(), (var.group(1).strip() if var else "")
    except OSError:
        pass
    try:
        with open("/etc/X11/xorg.conf.d/00-keyboard.conf") as f:
            txt = f.read()
        lay = re.search(r'"XkbLayout"\s+"([^",]+)', txt)
        if lay:
            return lay.group(1), ""
    except OSError:
        pass
    return "us", ""


class _RuleNames(ctypes.Structure):
    _fields_ = [(n, ctypes.c_char_p) for n in ("rules", "model", "layout", "variant", "options")]


_KEYMAP_CACHE: dict[tuple[str, str], tuple[dict, dict]] = {}


def _mapa_teclas(layout: str, variant: str) -> tuple[dict, dict]:
    """(char → (keycode_evdev, [mods_evdev]), keysym_muerta → idem) para la
    distribución. Prefiere la combinación con menos modificadores."""
    clave = (layout, variant)
    if clave in _KEYMAP_CACHE:
        return _KEYMAP_CACHE[clave]
    lib = ctypes.CDLL(ctypes.util.find_library("xkbcommon") or "libxkbcommon.so.0")
    lib.xkb_context_new.restype = ctypes.c_void_p
    lib.xkb_keymap_new_from_names.restype = ctypes.c_void_p
    lib.xkb_keymap_new_from_names.argtypes = [ctypes.c_void_p, ctypes.POINTER(_RuleNames), ctypes.c_int]
    for fn in ("xkb_keymap_min_keycode", "xkb_keymap_max_keycode"):
        getattr(lib, fn).argtypes = [ctypes.c_void_p]
    lib.xkb_keymap_num_levels_for_key.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_uint32]
    lib.xkb_keymap_key_get_syms_by_level.argtypes = [
        ctypes.c_void_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_uint32,
        ctypes.POINTER(ctypes.POINTER(ctypes.c_uint32))]
    lib.xkb_keymap_key_get_mods_for_level.restype = ctypes.c_size_t
    lib.xkb_keymap_key_get_mods_for_level.argtypes = [
        ctypes.c_void_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_uint32,
        ctypes.POINTER(ctypes.c_uint32), ctypes.c_size_t]
    lib.xkb_keymap_mod_get_index.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
    lib.xkb_keymap_mod_get_index.restype = ctypes.c_uint32
    lib.xkb_keysym_to_utf32.argtypes = [ctypes.c_uint32]
    lib.xkb_keysym_to_utf32.restype = ctypes.c_uint32
    lib.xkb_keymap_unref.argtypes = [ctypes.c_void_p]
    lib.xkb_context_unref.argtypes = [ctypes.c_void_p]

    ctx = lib.xkb_context_new(0)
    names = _RuleNames(b"evdev", b"pc105", layout.encode(), variant.encode(), None)
    km = lib.xkb_keymap_new_from_names(ctx, ctypes.byref(names), 0)
    if not km:
        lib.xkb_context_unref(ctx)
        raise RuntimeError(f"libxkbcommon no conoce la distribución {layout}")
    mod_evdev = {}
    for nombre, codigo in (("Shift", KEY_LEFTSHIFT), ("Mod5", KEY_RIGHTALT),
                           ("Control", KEY_LEFTCTRL), ("Mod1", KEY_LEFTALT)):
        idx = lib.xkb_keymap_mod_get_index(km, nombre.encode())
        if idx != 0xFFFFFFFF:
            mod_evdev[1 << idx] = codigo
    lock = lib.xkb_keymap_mod_get_index(km, b"Lock")
    lock_bit = (1 << lock) if lock != 0xFFFFFFFF else 0

    chars: dict[str, tuple[int, list[int]]] = {}
    muertas: dict[int, tuple[int, list[int]]] = {}
    syms = ctypes.POINTER(ctypes.c_uint32)()
    masks = (ctypes.c_uint32 * 16)()
    for kc in range(lib.xkb_keymap_min_keycode(km), lib.xkb_keymap_max_keycode(km) + 1):
        for level in range(lib.xkb_keymap_num_levels_for_key(km, kc, 0)):
            n = lib.xkb_keymap_key_get_syms_by_level(km, kc, 0, level, ctypes.byref(syms))
            if n != 1:
                continue
            sym = syms[0]
            nm = lib.xkb_keymap_key_get_mods_for_level(km, kc, 0, level, masks, 16)
            opciones = []
            for i in range(nm):
                m = masks[i]
                if m & lock_bit:
                    continue
                bits = [b for b in mod_evdev if m & b]
                if sum(bits) != m:
                    continue   # usa modificadores sin tecla física conocida
                opciones.append([mod_evdev[b] for b in bits])
            if not opciones:
                continue
            combo = (kc - 8, min(opciones, key=len))
            destino, clave_sym = (muertas, sym) if sym in _DEAD_KEYSYMS.values() else (None, None)
            if destino is not None:
                if clave_sym not in destino or _peso(combo) < _peso(destino[clave_sym]):
                    destino[clave_sym] = combo
                continue
            cp = lib.xkb_keysym_to_utf32(sym)
            if not cp:
                continue
            ch = chr(cp)
            if ch not in chars or _peso(combo) < _peso(chars[ch]):
                chars[ch] = combo
    lib.xkb_keymap_unref(km)
    lib.xkb_context_unref(ctx)
    chars.setdefault("\n", (28, []))
    chars.setdefault("\t", (15, []))
    for signo, marca in _SPACING_ACCENTS.items():
        if signo not in chars and _DEAD_KEYSYMS[marca] in muertas and " " in chars:
            chars[signo] = None  # se resuelve en _combos_para_texto: muerta + espacio
    _KEYMAP_CACHE[clave] = (chars, muertas)
    return chars, muertas


def _combo_payload(code: int, mods: list[int]) -> bytes:
    p = b""
    for m in mods:
        p += _event(EV_KEY, m, 1) + _SYN
    p += _event(EV_KEY, code, 1) + _SYN + _event(EV_KEY, code, 0) + _SYN
    for m in reversed(mods):
        p += _event(EV_KEY, m, 0) + _SYN
    return p


def _combos_para_texto(text: str) -> tuple[list[tuple[int, list[int]]], list[str]]:
    chars, muertas = _mapa_teclas(*_layout_actual())
    combos, faltan = [], []
    for ch in text.replace("\r\n", "\n"):
        if chars.get(ch) is not None:
            combos.append(chars[ch])
            continue
        if ch in chars:  # signo de tecla muerta: muerta + espacio
            combos += [muertas[_DEAD_KEYSYMS[_SPACING_ACCENTS[ch]]], chars[" "]]
            continue
        base, *marcas = unicodedata.normalize("NFD", ch)
        if len(marcas) == 1 and base in chars and _DEAD_KEYSYMS.get(marcas[0]) in muertas:
            combos += [muertas[_DEAD_KEYSYMS[marcas[0]]], chars[base]]
            continue
        faltan.append(ch)
    return combos, faltan


def _uinput_combos(combos: list[tuple[int, list[int]]]) -> str:
    for code, mods in combos:
        if not _YD.send(_combo_payload(code, mods)):
            return "no pude hablar con ydotoold (systemctl --user status ydotool)"
        time.sleep(0.004)
    return "ok"


def _uinput_key(key: str) -> str:
    """Tecla de la whitelist (formato "ctrl+shift+z", "F5", "super") por uinput."""
    if key == "super":
        return _uinput_combos([(KEY_LEFTMETA, [])])
    *mods, final = key.split("+")
    code = _EVDEV_KEYS.get(final)
    if code is None:
        chars, _ = _mapa_teclas(*_layout_actual())
        if chars.get(final) is None:
            return f"tecla desconocida en la distribución actual: {final}"
        code = chars[final][0]
    try:
        return _uinput_combos([(code, [_EVDEV_MODS[m] for m in mods])])
    except KeyError as e:
        return f"modificador desconocido: {e}"


def _uinput_text(text: str) -> str:
    try:
        combos, faltan = _combos_para_texto(text)
    except (OSError, RuntimeError) as e:
        return f"no pude cargar la distribución de teclado: {e}"
    result = _uinput_combos(combos)
    if result == "ok" and faltan:
        print(f"teclado: sin tecla en la distribución para {''.join(sorted(set(faltan)))[:40]!r}",
              file=sys.stderr)
    return result


# ─── Teclado (wtype) ──────────────────────────────────────────────────────────

def _wtype(args: list[str], timeout: int = 3) -> str:
    try:
        proc = subprocess.run(
            ["wtype", *args],
            capture_output=True, text=True, timeout=timeout, env=_env(),
        )
    except FileNotFoundError:
        return "wtype no instalado (sudo pacman -S wtype)"
    except subprocess.TimeoutExpired:
        return "timeout"
    if proc.returncode != 0:
        return (proc.stderr or proc.stdout or f"exit {proc.returncode}").strip()[:200]
    return "ok"


# Whitelist de teclas especiales → args para wtype.
_KEY_COMMANDS: dict[str, list[str]] = {
    "Return":       ["-k", "Return"],
    "Escape":       ["-k", "Escape"],
    "Tab":          ["-k", "Tab"],
    "BackSpace":    ["-k", "BackSpace"],
    "Delete":       ["-k", "Delete"],
    "Up":           ["-k", "Up"],
    "Down":         ["-k", "Down"],
    "Left":         ["-k", "Left"],
    "Right":        ["-k", "Right"],
    "Home":         ["-k", "Home"],
    "End":          ["-k", "End"],
    "Page_Up":      ["-k", "Page_Up"],
    "Page_Down":    ["-k", "Page_Down"],
    "ctrl+c":       ["-M", "ctrl", "-k", "c", "-m", "ctrl"],
    "ctrl+v":       ["-M", "ctrl", "-k", "v", "-m", "ctrl"],
    "ctrl+z":       ["-M", "ctrl", "-k", "z", "-m", "ctrl"],
    "ctrl+x":       ["-M", "ctrl", "-k", "x", "-m", "ctrl"],
    "ctrl+a":       ["-M", "ctrl", "-k", "a", "-m", "ctrl"],
    "ctrl+shift+z": ["-M", "ctrl", "-M", "shift", "-k", "z", "-m", "shift", "-m", "ctrl"],
    "ctrl+Return":  ["-M", "ctrl", "-k", "Return", "-m", "ctrl"],
    "alt+F4":       ["-M", "alt", "-k", "F4", "-m", "alt"],
    "alt+Tab":      ["-M", "alt", "-k", "Tab", "-m", "alt"],
    "super":        ["-k", "super_L"],
    "Print":        ["-k", "Print"],
    "F1":  ["-k", "F1"],  "F2":  ["-k", "F2"],  "F3":  ["-k", "F3"],
    "F4":  ["-k", "F4"],  "F5":  ["-k", "F5"],  "F6":  ["-k", "F6"],
    "F7":  ["-k", "F7"],  "F8":  ["-k", "F8"],  "F9":  ["-k", "F9"],
    "F10": ["-k", "F10"], "F11": ["-k", "F11"], "F12": ["-k", "F12"],
}


def key_press(key: str) -> str:
    args = _KEY_COMMANDS.get(key)
    if args is None:
        return f"tecla no permitida: {key}"
    result = _wtype(args)
    if _UNSUPPORTED_MSG in result:
        return _uinput_key(key)
    return result


def type_text(text: str) -> str:
    if not text:
        return "texto vacío"
    if len(text) > 2000:
        return "texto demasiado largo (max 2000 chars)"
    result = _wtype([text])
    if _UNSUPPORTED_MSG in result:
        return _uinput_text(text)
    return result

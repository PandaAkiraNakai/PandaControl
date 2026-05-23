"""Control de Brave/Chromium desde Panda Control.

Dos capas:

1. CDP (Chrome DevTools Protocol) sobre el puerto de remote-debugging del
   navegador (por defecto 127.0.0.1:9222) para listar pestañas, navegar,
   activar/cerrar, recargar e ir atrás/adelante. Brave debe arrancar con
   `--remote-debugging-port=9222 --remote-allow-origins=*` (se configura en
   ~/.config/brave-flags.conf, que el wrapper /usr/bin/brave lee siempre).

2. Búsqueda de YouTube por HTTP: se baja la página de resultados y se parsea
   `ytInitialData` del HTML. No necesita abrir el navegador ni API key.

El cliente WebSocket es mínimo (RFC 6455, solo lo que CDP necesita) para no
depender de librerías externas: el backend corre con Python stdlib + requests.
"""

from __future__ import annotations

import base64
import html
import json
import os
import re
import socket
import struct
import subprocess
import urllib.parse
import urllib.request

import requests

CDP_HOST = "127.0.0.1"
CDP_PORT = 9222
_UA = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
)


# ─── Cliente WebSocket mínimo para CDP ────────────────────────────────────────

class _WS:
    """Cliente WebSocket cliente-enmascarado, solo frames de texto."""

    def __init__(self, url: str, origin: str, timeout: float = 10.0):
        u = urllib.parse.urlparse(url)
        self.sock = socket.create_connection((u.hostname, u.port), timeout=timeout)
        self.sock.settimeout(timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        path = u.path + ("?" + u.query if u.query else "")
        req = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {u.hostname}:{u.port}\r\n"
            "Upgrade: websocket\r\nConnection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            f"Origin: {origin}\r\n\r\n"
        )
        self.sock.sendall(req.encode())
        resp = self._read_until(b"\r\n\r\n")
        if b"101" not in resp.split(b"\r\n", 1)[0]:
            raise RuntimeError("handshake WS falló: " + resp.decode("latin1")[:200])
        self._buf = b""

    def _read_until(self, sep: bytes) -> bytes:
        data = b""
        while sep not in data:
            chunk = self.sock.recv(4096)
            if not chunk:
                break
            data += chunk
        return data

    def _send(self, text: str) -> None:
        payload = text.encode()
        header = bytearray([0x81])  # FIN + opcode texto
        n = len(payload)
        mask = os.urandom(4)
        if n < 126:
            header.append(0x80 | n)
        elif n < 65536:
            header.append(0x80 | 126)
            header += struct.pack(">H", n)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", n)
        header += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def _recv(self) -> tuple[int, str]:
        b0 = self._recvn(1)[0]
        opcode = b0 & 0x0F
        b1 = self._recvn(1)[0]
        n = b1 & 0x7F
        if n == 126:
            n = struct.unpack(">H", self._recvn(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", self._recvn(8))[0]
        data = self._recvn(n)
        return opcode, data.decode("utf-8", "replace")

    def _recvn(self, n: int) -> bytes:
        while len(self._buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise RuntimeError("socket WS cerrado")
            self._buf += chunk
        out, self._buf = self._buf[:n], self._buf[n:]
        return out

    def call(self, mid: int, method: str, params: dict | None = None) -> dict:
        self._send(json.dumps({"id": mid, "method": method, "params": params or {}}))
        while True:
            op, msg = self._recv()
            if op == 8:  # close
                raise RuntimeError("el navegador cerró el WS")
            d = json.loads(msg)
            if d.get("id") == mid:
                return d

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


# ─── Helpers CDP HTTP ─────────────────────────────────────────────────────────

def _http_json(path: str, port: int = CDP_PORT, timeout: float = 4.0):
    url = f"http://{CDP_HOST}:{port}{path}"
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return json.load(r)

def _http_text(path: str, port: int = CDP_PORT, timeout: float = 4.0) -> str:
    url = f"http://{CDP_HOST}:{port}{path}"
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


def cdp_available(port: int = CDP_PORT) -> bool:
    try:
        _http_json("/json/version", port)
        return True
    except Exception:
        return False


def _pages(port: int = CDP_PORT) -> list[dict]:
    targets = _http_json("/json", port)
    out = []
    for t in targets:
        if t.get("type") != "page":
            continue
        url = t.get("url", "")
        if url.startswith("devtools://"):
            continue
        out.append(t)
    return out


def _ws_call(target_id: str, method: str, params: dict | None = None,
             port: int = CDP_PORT) -> dict:
    t = next((x for x in _pages(port) if x["id"] == target_id), None)
    if t is None:
        raise ValueError("pestaña no encontrada")
    ws = _WS(t["webSocketDebuggerUrl"], f"http://{CDP_HOST}:{port}")
    try:
        return ws.call(1, method, params)
    finally:
        ws.close()


def _eval(target_id: str, expression: str, port: int = CDP_PORT):
    """Evalúa JS en la pestaña y devuelve el valor (returnByValue)."""
    d = _ws_call(
        target_id, "Runtime.evaluate",
        {"expression": expression, "returnByValue": True}, port,
    )
    try:
        return d["result"]["result"].get("value")
    except (KeyError, TypeError):
        return None


# ─── Operaciones de navegador ─────────────────────────────────────────────────

def list_tabs(port: int = CDP_PORT) -> list[dict]:
    """Pestañas abiertas (type=page), sin las internas de devtools."""
    return [
        {"id": t["id"], "title": t.get("title", ""), "url": t.get("url", "")}
        for t in _pages(port)
    ]


def _brave_env() -> dict:
    uid = os.getuid()
    return {
        **os.environ,
        "XDG_RUNTIME_DIR": f"/run/user/{uid}",
        "WAYLAND_DISPLAY": "wayland-1",
    }


def _looks_like_url(s: str) -> bool:
    s = s.strip()
    if s.startswith(("http://", "https://", "about:", "file://")):
        return True
    # dominio simple tipo "github.com" o "github.com/path"
    return bool(re.match(r"^[\w-]+(\.[\w-]+)+(/\S*)?$", s))


def to_target_url(text: str) -> str:
    """Convierte lo que escribió el usuario en una URL: URL directa, o búsqueda
    en Brave Search si no parece URL."""
    s = text.strip()
    if _looks_like_url(s):
        return s if "://" in s else "https://" + s
    q = urllib.parse.quote(s)
    return f"https://search.brave.com/search?q={q}"


def open_url(text: str) -> str:
    """Abre una URL/búsqueda en Brave (nueva pestaña en la instancia activa)."""
    url = to_target_url(text)
    try:
        subprocess.Popen(
            ["brave", url],
            env=_brave_env(),
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
    except FileNotFoundError:
        return "brave no encontrado"
    return "ok"


def navigate(target_id: str, text: str, port: int = CDP_PORT) -> str:
    _ws_call(target_id, "Page.navigate", {"url": to_target_url(text)}, port)
    return "ok"


def activate(target_id: str, port: int = CDP_PORT) -> str:
    _http_text(f"/json/activate/{target_id}", port)
    return "ok"


def close_tab(target_id: str, port: int = CDP_PORT) -> str:
    _http_text(f"/json/close/{target_id}", port)
    return "ok"


def reload(target_id: str, port: int = CDP_PORT) -> str:
    _ws_call(target_id, "Page.reload", {}, port)
    return "ok"


def go_back(target_id: str, port: int = CDP_PORT) -> str:
    _ws_call(target_id, "Runtime.evaluate", {"expression": "history.back()"}, port)
    return "ok"


def go_forward(target_id: str, port: int = CDP_PORT) -> str:
    _ws_call(target_id, "Runtime.evaluate", {"expression": "history.forward()"}, port)
    return "ok"


# ─── Interacción con la página (scroll / clic / escribir) ─────────────────────

_SCROLL_AMOUNT = {"down": 0.85, "up": -0.85, "top": -1e7, "bottom": 1e7}


def scroll(target_id: str, direction: str, port: int = CDP_PORT) -> str:
    """Desplaza la pestaña enviando un evento de rueda real (CDP Input). A
    diferencia de window.scrollBy, esto desplaza el contenedor de scroll que
    esté bajo el cursor, así funciona en sitios con scroll interno (DDG, apps
    SPA, etc.). top/bottom usan un delta enorme que la rueda clampa al borde."""
    factor = _SCROLL_AMOUNT.get(direction)
    if factor is None:
        return "dirección inválida"
    size = _eval(target_id, "[innerWidth, innerHeight]", port) or [1000, 800]
    w, h = (list(size) + [1000, 800])[:2]
    delta = factor * h if abs(factor) <= 1 else factor
    _ws_call(target_id, "Input.dispatchMouseEvent", {
        "type": "mouseWheel",
        "x": int(w / 2), "y": int(h / 2),
        "deltaX": 0, "deltaY": int(delta),
    }, port)
    return "ok"


def click_text(target_id: str, text: str, port: int = CDP_PORT) -> str:
    """Hace clic en el enlace/botón VISIBLE cuyo texto contenga `text`. Si hay
    varios, elige el de texto más corto (el match más específico)."""
    expr = (
        "(()=>{const q=%s.toLowerCase().trim();"
        "const vis=e=>{const r=e.getBoundingClientRect();"
        "return e.offsetParent!==null&&r.width>0&&r.height>0;};"
        "const els=[...document.querySelectorAll('a,button,[role=button],"
        "[role=link],input[type=submit],input[type=button],summary,[onclick]')];"
        "let best=null,bl=1e9;"
        "for(const e of els){if(!vis(e))continue;"
        "const t=(e.innerText||e.value||e.getAttribute('aria-label')||'').trim().toLowerCase();"
        "if(t&&t.includes(q)&&t.length<bl){best=e;bl=t.length;}}"
        "if(!best)return 'sin coincidencia';"
        "best.scrollIntoView({block:'center'});best.click();return 'ok';})()"
    ) % json.dumps(text)
    return _eval(target_id, expr, port) or "sin coincidencia"


# JS para etiquetar y listar elementos clicables. La etiqueta sale del texto
# visible o, si no hay (banners/posters), del aria-label / title / alt de la
# imagen anidada. Cada elemento queda marcado con data-panda-idx para que
# click_index pueda clicarlo después aunque el DOM cambie de orden.
_PAGE_LINKS_JS = (
    "(()=>{const LIMIT=%d;"
    "const chrome=e=>e.closest('header,nav,[role=navigation],[role=banner],"
    "[role=menubar],[role=toolbar],[aria-label=\"Navegación\"]');"
    "const vis=e=>{const r=e.getBoundingClientRect();"
    "return e.offsetParent!==null&&r.width>=24&&r.height>=24&&!chrome(e);};"
    "const label=e=>{let t=(e.innerText||'').trim();"
    "if(!t)t=(e.getAttribute('aria-label')||'').trim();"
    "if(!t)t=(e.getAttribute('title')||'').trim();"
    "if(!t){const im=e.querySelector('img[alt]');if(im)t=(im.getAttribute('alt')||'').trim();}"
    "if(!t){const a=e.querySelector('[aria-label]');if(a)t=(a.getAttribute('aria-label')||'').trim();}"
    "if(!t){const im=e.querySelector('img[title]');if(im)t=(im.getAttribute('title')||'').trim();}"
    "return t.replace(/\\s+/g,' ').slice(0,90);};"
    "const els=[...document.querySelectorAll('a[href],button,[role=button],"
    "[role=link],[onclick]')];const out=[];let i=0;"
    "for(const e of els){if(!vis(e))continue;const t=label(e);if(!t)continue;"
    "e.setAttribute('data-panda-idx',i);out.push({idx:i,label:t});i++;"
    "if(out.length>=LIMIT)break;}return out;})()"
)


def page_links(target_id: str, limit: int = 40, port: int = CDP_PORT) -> list[dict]:
    """Lista elementos clicables VISIBLES de la página con su etiqueta (texto,
    o aria-label/alt de la imagen para banners sin texto). Marca cada uno con
    data-panda-idx para clicarlo luego con click_index."""
    out = _eval(target_id, _PAGE_LINKS_JS % int(limit), port)
    return out if isinstance(out, list) else []


def click_index(target_id: str, idx: int, port: int = CDP_PORT) -> str:
    """Hace clic en el elemento marcado con data-panda-idx=`idx` por page_links."""
    expr = (
        '(()=>{const e=document.querySelector(\'[data-panda-idx="%d"]\');'
        "if(!e)return 'no encontrado';e.scrollIntoView({block:'center'});"
        "e.click();return 'ok';})()"
    ) % int(idx)
    return _eval(target_id, expr, port) or "no encontrado"


def type_text(target_id: str, text: str, submit: bool = False,
              port: int = CDP_PORT) -> str:
    """Escribe en el campo enfocado, o en el campo de texto visible más grande.
    Usa el setter nativo de value para que inputs controlados (React/Vue)
    registren el cambio. Si `submit`, envía el formulario o Enter."""
    expr = (
        "(()=>{const v=%s;"
        "const typ=e=>e&&(e.tagName==='INPUT'||e.tagName==='TEXTAREA'||e.isContentEditable);"
        "const vis=e=>e&&e.offsetParent!==null&&!e.disabled&&!e.readOnly;"
        "let el=document.activeElement;"
        "if(!(typ(el)&&vis(el))){"
        "const c=[...document.querySelectorAll('input[type=search],input[type=text],"
        "input[type=email],input[type=url],input[type=tel],input:not([type]),"
        "textarea,[contenteditable=true]')].filter(vis);"
        "c.sort((a,b)=>(b.offsetWidth*b.offsetHeight)-(a.offsetWidth*a.offsetHeight));"
        "el=c[0];}"
        "if(!typ(el))return 'sin campo';el.focus();"
        "if(el.isContentEditable){el.textContent=v;}else{"
        "const p=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
        "Object.getOwnPropertyDescriptor(p,'value').set.call(el,v);}"
        "el.dispatchEvent(new Event('input',{bubbles:true}));"
        "el.dispatchEvent(new Event('change',{bubbles:true}));"
        "if(%s){const f=el.form;if(f){f.requestSubmit?f.requestSubmit():f.submit();}"
        "else{['keydown','keypress','keyup'].forEach(t=>el.dispatchEvent("
        "new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true})));}}"
        "return 'ok';})()"
    ) % (json.dumps(text), "true" if submit else "false")
    return _eval(target_id, expr, port) or "sin campo"


# ─── Búsqueda web (DuckDuckGo HTML, sin navegador) ────────────────────────────

_DDG_HTML = "https://html.duckduckgo.com/html/"


def _strip_tags(s: str) -> str:
    return html.unescape(re.sub(r"<[^>]+>", "", s)).strip()


def _ddg_unwrap(href: str) -> str:
    """DuckDuckGo a veces envuelve la URL en /l/?uddg=<url>; la desenvuelve."""
    if href.startswith("//"):
        href = "https:" + href
    p = urllib.parse.urlparse(href)
    if "duckduckgo.com" in p.netloc and p.path.startswith("/l/"):
        qs = urllib.parse.parse_qs(p.query)
        if qs.get("uddg"):
            return qs["uddg"][0]
    return href


def web_search(query: str, limit: int = 12) -> list[dict]:
    """Busca en la web y devuelve resultados (título + URL + descripción)
    parseando el HTML liviano de DuckDuckGo. Los links se abren luego en Brave."""
    r = requests.post(
        _DDG_HTML,
        data={"q": query},
        headers={"User-Agent": _UA, "Accept-Language": "es-CL,es;q=0.9,en;q=0.8"},
        timeout=10,
    )
    r.raise_for_status()
    text = r.text
    out: list[dict] = []
    for m in re.finditer(
        r'class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>', text, re.S
    ):
        href = _ddg_unwrap(html.unescape(m.group(1)))
        title = _strip_tags(m.group(2))
        if not href or not title:
            continue
        # Salta anuncios / links internos de DuckDuckGo (los resultados reales
        # siempre apuntan a un dominio externo).
        if "duckduckgo.com" in urllib.parse.urlparse(href).netloc:
            continue
        sm = re.search(
            r'class="result__snippet"[^>]*>(.*?)</a>', text[m.end():m.end() + 1500], re.S
        )
        out.append({
            "title": title,
            "url": href,
            "description": _strip_tags(sm.group(1)) if sm else "",
        })
        if len(out) >= limit:
            break
    return out


# ─── Búsqueda de YouTube (HTTP, sin navegador) ────────────────────────────────

def youtube_search(query: str, limit: int = 20) -> list[dict]:
    """Busca en YouTube parseando ytInitialData del HTML de resultados."""
    url = "https://www.youtube.com/results?search_query=" + urllib.parse.quote(query)
    r = requests.get(
        url,
        headers={"User-Agent": _UA, "Accept-Language": "es-CL,es;q=0.9,en;q=0.8"},
        cookies={"CONSENT": "YES+1", "PREF": "hl=es"},
        timeout=10,
    )
    r.raise_for_status()
    m = re.search(r"ytInitialData\s*=\s*(\{.*?\});</script>", r.text)
    if not m:
        m = re.search(r"var ytInitialData = (\{.*?\});", r.text)
    if not m:
        return []
    data = json.loads(m.group(1))
    try:
        sections = (
            data["contents"]["twoColumnSearchResultsRenderer"]
            ["primaryContents"]["sectionListRenderer"]["contents"]
        )
    except (KeyError, TypeError):
        return []
    vids: list[dict] = []
    for sec in sections:
        for it in sec.get("itemSectionRenderer", {}).get("contents", []):
            v = it.get("videoRenderer")
            if not v or "videoId" not in v:
                continue
            title = "".join(t.get("text", "") for t in v.get("title", {}).get("runs", []))
            channel = ""
            owner = v.get("ownerText", {}).get("runs", [])
            if owner:
                channel = owner[0].get("text", "")
            thumbs = v.get("thumbnail", {}).get("thumbnails", [])
            vids.append({
                "id": v["videoId"],
                "title": title,
                "channel": channel,
                "duration": v.get("lengthText", {}).get("simpleText", ""),
                "views": v.get("viewCountText", {}).get("simpleText", ""),
                "thumbnail": thumbs[-1]["url"] if thumbs else "",
            })
            if len(vids) >= limit:
                return vids
    return vids


def youtube_play(video_id: str, target_id: str | None = None,
                 port: int = CDP_PORT) -> str:
    """Reproduce un video de YouTube en Brave. Si se pasa target_id, navega esa
    pestaña; si no, abre una nueva."""
    if not re.match(r"^[\w-]{6,20}$", video_id):
        return "videoId inválido"
    url = f"https://www.youtube.com/watch?v={video_id}"
    if target_id:
        _ws_call(target_id, "Page.navigate", {"url": url}, port)
        return "ok"
    return open_url(url)

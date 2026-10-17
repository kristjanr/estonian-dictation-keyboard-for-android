"""HTTP -> WebSocket bridge for tekstiks.ee speech recognition.

iOS Shortcuts can POST a file but cannot speak WebSocket, which is the only way
tekstiks.ee does live recognition (wss://tekstiks.ee/asr/v2, undocumented; the
protocol was read from the site's /dictate page). This service takes an audio
file in a POST, streams it to that socket and returns the transcript as plain
text.

    curl --data-binary @memo.m4a -H "Authorization: Bearer $TOKEN" \
         https://<host>/transcribe
"""

import asyncio
import base64
import json
import logging
import os
import tempfile
import time

from aiohttp import ClientSession, WSMsgType, web

UPSTREAM = os.environ.get("UPSTREAM_URL", "wss://tekstiks.ee/asr/v2")
TOKEN = os.environ.get("PROXY_TOKEN", "")
MAX_UPLOAD_MB = int(os.environ.get("MAX_UPLOAD_MB", "25"))
MAX_SECONDS = int(os.environ.get("MAX_AUDIO_SECONDS", "600"))
LANGUAGES = {"et", "fastconformer_transducer_en_80ms", "fastconformer_transducer_en_1040ms"}

RATE = 16000
BYTES_PER_SEC = RATE * 2  # 16-bit mono PCM
CHUNK = BYTES_PER_SEC // 2

# The server sends no usable "final" result: every partial is the whole
# utterance so far, and the one closing message is "[Session Ended]". Sending
# "stop" also discards audio it has not decoded yet. So we send everything,
# then wait until the transcript has stopped changing for QUIET seconds.
QUIET = float(os.environ.get("QUIET_SECONDS", "2.5"))
TRAILING_SILENCE = b"\0" * BYTES_PER_SEC

log = logging.getLogger("tekstiks-proxy")


class BadAudio(Exception):
    pass


async def to_pcm(data: bytes) -> bytes:
    # A temp file rather than a pipe: iPhone .m4a files often put their index
    # (moov atom) at the end, which ffmpeg cannot read from a non-seekable pipe.
    with tempfile.NamedTemporaryFile() as f:
        f.write(data)
        f.flush()
        proc = await asyncio.create_subprocess_exec(
            "ffmpeg", "-nostdin", "-loglevel", "error", "-i", f.name,
            "-t", str(MAX_SECONDS), "-ar", str(RATE), "-ac", "1", "-f", "s16le", "pipe:1",
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        )
        out, err = await proc.communicate()
    if proc.returncode != 0 or not out:
        lines = err.decode(errors="replace").strip().splitlines()
        raise BadAudio(lines[-1] if lines else "no audio decoded")
    return out


class Transcript:
    """Joins partials into text.

    Each partial normally extends the previous one, so the latest wins. If the
    server ever starts a fresh segment (a partial that does not continue the
    previous one), the old text is kept rather than overwritten.
    """

    def __init__(self):
        self.done: list[str] = []
        self.current = ""

    def update(self, text: str):
        text = text.strip()
        if not text:
            return
        prev = self.current
        anchor = prev[: max(1, min(len(prev), 12) // 2)]
        if prev and not text.startswith(anchor):
            self.done.append(prev)
        self.current = text

    def text(self) -> str:
        return " ".join(self.done + ([self.current] if self.current else [])).strip()


async def recognize(session: ClientSession, pcm: bytes, language: str) -> str:
    transcript = Transcript()
    last_activity = time.monotonic()
    sent_all = asyncio.Event()

    async with session.ws_connect(UPSTREAM, heartbeat=20, max_msg_size=0) as ws:
        await ws.send_str(json.dumps({"type": "start", "sample_rate": RATE, "format": "pcm", "language": language}))
        msg = await ws.receive(timeout=15)
        ready = json.loads(msg.data) if msg.type == WSMsgType.TEXT else {}
        if ready.get("type") != "ready":
            raise RuntimeError(f"upstream did not accept session: {msg.data!r}")

        async def send():
            nonlocal last_activity
            audio = pcm + TRAILING_SILENCE
            for i in range(0, len(audio), CHUNK):
                payload = base64.b64encode(audio[i : i + CHUNK]).decode()
                await ws.send_str(json.dumps({"type": "audio", "data": payload}))
            last_activity = time.monotonic()
            sent_all.set()

        async def receive():
            nonlocal last_activity
            async for m in ws:
                if m.type != WSMsgType.TEXT:
                    continue
                d = json.loads(m.data)
                if d.get("type") == "transcript":
                    if d.get("text", "").strip() == "[Session Ended]":
                        return
                    transcript.update(d.get("text", ""))
                    last_activity = time.monotonic()
                elif d.get("type") == "error":
                    raise RuntimeError(f"upstream error: {d.get('message')}")
                elif d.get("type") == "session_ended":
                    return

        sender = asyncio.create_task(send())
        receiver = asyncio.create_task(receive())
        deadline = time.monotonic() + 30 + len(pcm) / BYTES_PER_SEC
        try:
            while not receiver.done():
                if sender.done() and sender.exception():
                    raise sender.exception()
                now = time.monotonic()
                if sent_all.is_set() and now - last_activity > QUIET:
                    break
                if now > deadline:
                    log.warning("deadline hit; returning partial transcript")
                    break
                await asyncio.sleep(0.1)
            if receiver.done() and receiver.exception():
                raise receiver.exception()
            if not ws.closed:
                await ws.send_str(json.dumps({"type": "stop"}))
        finally:
            sender.cancel()
            receiver.cancel()
    return transcript.text()


def authorized(request: web.Request) -> bool:
    if not TOKEN:
        return True
    header = request.headers.get("Authorization", "")
    return header == f"Bearer {TOKEN}" or request.query.get("token") == TOKEN


async def read_audio(request: web.Request) -> bytes:
    # Shortcuts can send the file either as the raw body ("File") or as a form
    # field ("Form"); accept both so either setting works.
    if request.content_type.startswith("multipart/"):
        reader = await request.multipart()
        async for part in reader:
            if part.filename or part.name in ("file", "audio"):
                return await part.read(decode=False)
        return b""
    return await request.read()


async def transcribe(request: web.Request) -> web.Response:
    if not authorized(request):
        return web.Response(status=401, text="unauthorized\n")
    language = request.query.get("lang", "et")
    if language not in LANGUAGES:
        return web.Response(status=400, text=f"lang must be one of {sorted(LANGUAGES)}\n")

    data = await read_audio(request)
    if not data:
        return web.Response(status=400, text="no audio in request body\n")

    t0 = time.monotonic()
    try:
        pcm = await to_pcm(data)
    except BadAudio as e:
        return web.Response(status=415, text=f"could not decode audio: {e}\n")

    try:
        text = await recognize(request.app["http"], pcm, language)
    except Exception as e:
        log.exception("recognition failed")
        return web.Response(status=502, text=f"recognition failed: {e}\n")

    log.info("%.1fs audio -> %d chars in %.1fs", len(pcm) / BYTES_PER_SEC, len(text), time.monotonic() - t0)
    return web.Response(text=text, content_type="text/plain", charset="utf-8")


async def health(_: web.Request) -> web.Response:
    return web.Response(text="ok\n")


async def make_app() -> web.Application:
    app = web.Application(client_max_size=MAX_UPLOAD_MB * 1024 * 1024)
    app.router.add_post("/transcribe", transcribe)
    app.router.add_get("/", health)

    async def http_session(app):
        app["http"] = ClientSession()
        yield
        await app["http"].close()

    app.cleanup_ctx.append(http_session)
    return app


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if not TOKEN:
        log.warning("PROXY_TOKEN is not set: anyone who finds this URL can use it")
    web.run_app(make_app(), port=int(os.environ.get("PORT", "8080")))

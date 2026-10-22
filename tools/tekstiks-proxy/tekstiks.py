"""Recognition through tekstiks.ee's live WebSocket (wss://tekstiks.ee/asr/v2).

Undocumented: the protocol was read from the site's /dictate page. Kept as a
fallback backend (BACKEND=tekstiks); the default runs the model locally.
"""

import asyncio
import base64
import json
import logging
import os
import time

from aiohttp import ClientSession, WSMsgType

UPSTREAM = os.environ.get("UPSTREAM_URL", "wss://tekstiks.ee/asr/v2")
LANGUAGES = {"et", "fastconformer_transducer_en_80ms", "fastconformer_transducer_en_1040ms"}

RATE = 16000
BYTES_PER_SEC = RATE * 2  # 16-bit mono PCM
CHUNK = BYTES_PER_SEC // 2

# The server sends no usable "final" result: every partial is the whole
# utterance so far, and the one closing message is "[Session Ended]". Sending
# "stop" also discards audio it has not decoded yet. So we send everything,
# then wait until the transcript has stopped changing for QUIET seconds.
QUIET = float(os.environ.get("QUIET_SECONDS", "2.5"))

# When tekstiks.ee's recogniser is down, its nginx still accepts the connection
# and then never answers. Without a limit the request hangs until the iOS
# Shortcut gives up with a bare "request timed out".
CONNECT_TIMEOUT = float(os.environ.get("CONNECT_TIMEOUT_SECONDS", "10"))
TRAILING_SILENCE = b"\0" * BYTES_PER_SEC

log = logging.getLogger("tekstiks-proxy")


class UpstreamDown(Exception):
    pass


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

    try:
        ws = await asyncio.wait_for(
            session.ws_connect(UPSTREAM, heartbeat=20, max_msg_size=0), CONNECT_TIMEOUT)
    except (asyncio.TimeoutError, OSError) as e:
        raise UpstreamDown(f"could not connect ({type(e).__name__})") from e

    async with ws:
        await ws.send_str(json.dumps({"type": "start", "sample_rate": RATE, "format": "pcm", "language": language}))
        try:
            msg = await ws.receive(timeout=CONNECT_TIMEOUT)
        except asyncio.TimeoutError as e:
            raise UpstreamDown("connected, but no reply to start") from e
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

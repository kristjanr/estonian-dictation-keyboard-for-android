"""POST an audio file, get Estonian text back.

iOS Shortcuts can POST a file and read the reply, nothing more. This service
decodes the upload with ffmpeg and runs TalTech's Zipformer on it in-process
(BACKEND=local, the default), or relays it to tekstiks.ee's live recogniser
(BACKEND=tekstiks).

    curl --data-binary @memo.m4a -H "Authorization: Bearer $TOKEN" \
         https://<host>/transcribe
"""

import asyncio
import logging
import os
import tempfile
import time

from aiohttp import ClientSession, web

import tekstiks

BACKEND = os.environ.get("BACKEND", "local")
TOKEN = os.environ.get("PROXY_TOKEN", "")
MAX_UPLOAD_MB = int(os.environ.get("MAX_UPLOAD_MB", "25"))
MAX_SECONDS = int(os.environ.get("MAX_AUDIO_SECONDS", "600"))

RATE = 16000
BYTES_PER_SEC = RATE * 2  # 16-bit mono PCM

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


def tidy(text: str) -> str:
    # The Zipformer punctuates from what follows each word, so the start stays
    # lowercase and the last sentence gets no full stop (or ends on a comma).
    text = text.strip()
    if not text:
        return text
    if text.endswith(","):
        text = text[:-1] + "."
    elif text[-1].isalnum():
        text += "."
    return text[0].upper() + text[1:]


def error(status: int, message: str) -> web.Response:
    # Shortcuts passes the body of a failed request on as if it were the
    # result, so every failure starts with "ERROR:" for the Shortcut to check
    # before it sends anything to anyone.
    return web.Response(status=status, text=f"ERROR: {message}\n")


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
        return error(401, "wrong or missing token")
    language = request.query.get("lang", "et")
    if BACKEND == "tekstiks" and language not in tekstiks.LANGUAGES:
        return error(400, f"lang must be one of {sorted(tekstiks.LANGUAGES)}")

    data = await read_audio(request)
    if not data:
        return error(400, "no audio in request body")

    t0 = time.monotonic()
    try:
        pcm = await to_pcm(data)
    except BadAudio as e:
        return error(415, f"could not decode audio: {e}")

    try:
        if BACKEND == "tekstiks":
            text = await tekstiks.recognize(request.app["http"], pcm, language)
        else:
            # The local model is bilingual (et/en) and takes no language setting.
            text = await request.app["asr"].transcribe(pcm)
    except tekstiks.UpstreamDown as e:
        log.warning("tekstiks.ee unavailable: %s", e)
        return error(503, "tekstiks.ee is not responding right now. Try again later.")
    except Exception as e:
        log.exception("recognition failed")
        return error(502, f"recognition failed: {e}")

    text = tidy(text)
    if not text:
        return error(422, "no speech recognised")

    log.info("%.1fs audio -> %d chars in %.2fs", len(pcm) / BYTES_PER_SEC, len(text), time.monotonic() - t0)
    return web.Response(text=text, content_type="text/plain", charset="utf-8")


async def health(_: web.Request) -> web.Response:
    return web.Response(text="ok\n")


def make_app() -> web.Application:
    app = web.Application(client_max_size=MAX_UPLOAD_MB * 1024 * 1024)
    app.router.add_post("/transcribe", transcribe)
    app.router.add_get("/", health)

    if BACKEND == "tekstiks":
        async def http_session(app):
            app["http"] = ClientSession()
            yield
            await app["http"].close()

        app.cleanup_ctx.append(http_session)
    else:
        import local_asr

        # Load before the server starts listening: Cloud Run sends no traffic to
        # an instance until its port is open, so no request ever waits on this.
        app["asr"] = local_asr.from_env()
    return app


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if BACKEND not in ("local", "tekstiks"):
        raise SystemExit(f"BACKEND must be 'local' or 'tekstiks', not {BACKEND!r}")
    if not TOKEN:
        log.warning("PROXY_TOKEN is not set: anyone who finds this URL can use it")
    log.info("backend: %s", BACKEND)
    web.run_app(make_app(), port=int(os.environ.get("PORT", "8080")))

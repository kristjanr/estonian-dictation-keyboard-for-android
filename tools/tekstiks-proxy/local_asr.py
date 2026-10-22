"""Recognition with TalTech's streaming Zipformer, run in this process.

Model: TalTechNLP/streaming-zipformer-large.et-en (MIT), a single bilingual
Estonian/English model that punctuates by itself. The Dockerfile bakes it into
the image. The same model powers the Android keyboard in this repo.
"""

import asyncio
import logging
import os
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import sherpa_onnx

RATE = 16000
# Half a second of silence after the speech lets the model emit the last
# word; without it the final token is sometimes dropped.
TAIL = np.zeros(RATE // 2, dtype=np.float32)

log = logging.getLogger("tekstiks-proxy")


def _pick(model_dir: Path, name: str) -> str:
    # Prefer quantised weights, fall back to fp32: the same rule as the app's
    # ModelStore, so either download works.
    for candidate in (f"{name}.int8.onnx", f"{name}.onnx"):
        if (model_dir / candidate).exists():
            return str(model_dir / candidate)
    raise FileNotFoundError(f"no {name}*.onnx in {model_dir}")


class LocalRecognizer:
    def __init__(self, model_dir: str, threads: int):
        d = Path(model_dir)
        t0 = time.monotonic()
        self.rec = sherpa_onnx.OnlineRecognizer.from_transducer(
            tokens=str(d / "tokens.txt"),
            encoder=_pick(d, "encoder"),
            decoder=_pick(d, "decoder"),
            joiner=_pick(d, "joiner"),
            num_threads=threads,
            sample_rate=RATE,
            feature_dim=80,
            decoding_method="greedy_search",
        )
        # sherpa-onnx objects are not thread-safe, so every decode runs on one
        # worker thread; that also keeps decoding off the event loop.
        self.pool = ThreadPoolExecutor(max_workers=1)
        # onnxruntime finishes setting up on the first inference, which made the
        # first real request ~3 s slower. Pay that here, before serving.
        self._decode(bytes(RATE * 2))
        log.info("model loaded and warmed up from %s in %.1fs", d, time.monotonic() - t0)

    def _decode(self, pcm: bytes) -> str:
        samples = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768
        stream = self.rec.create_stream()
        stream.accept_waveform(RATE, samples)
        stream.accept_waveform(RATE, TAIL)
        stream.input_finished()
        while self.rec.is_ready(stream):
            self.rec.decode_stream(stream)
        return self.rec.get_result(stream)

    async def transcribe(self, pcm: bytes) -> str:
        return await asyncio.get_running_loop().run_in_executor(self.pool, self._decode, pcm)


def from_env() -> LocalRecognizer:
    return LocalRecognizer(
        os.environ.get("MODEL_DIR", "/model"),
        int(os.environ.get("ASR_THREADS", "1")),
    )

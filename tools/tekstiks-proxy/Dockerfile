FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Bake the model into the image so a new instance never downloads it. Pinned to
# a Hugging Face commit so rebuilds are reproducible; bump MODEL_REV to update.
# int8 is ~156 MB and about as accurate as fp32 for this model.
ARG MODEL_REPO=TalTechNLP/streaming-zipformer-large.et-en
ARG MODEL_REV=2812262bd4fd5d4327dc09ca0e6c19e8a32a03d6
RUN mkdir /model && python -c "import sys, urllib.request; \
[urllib.request.urlretrieve(f'https://huggingface.co/{sys.argv[1]}/resolve/{sys.argv[2]}/{f}', f'/model/{f}') \
 for f in ('encoder.int8.onnx', 'decoder.int8.onnx', 'joiner.int8.onnx', 'tokens.txt')]" \
  "$MODEL_REPO" "$MODEL_REV" && ls -la /model

COPY *.py ./
ENV PYTHONUNBUFFERED=1 MODEL_DIR=/model
CMD ["python", "app.py"]

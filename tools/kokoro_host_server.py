#!/usr/bin/env python3
"""
Development tool: exposes the real Kokoro-82M model (via MediaPipe's Python TextToSpeech task,
the same runtime and .litertlm bundle used on Android) to the JVM integration test
`RealKokoroPipelineTest`, so the complete Oralis generation pipeline can be validated on a
Linux host without an Android device.

Protocol (stdin/stdout, one JSON object per line):
  request : {"text": str, "voice": str, "locale": str|null, "rate": float, "out": path}
  response: {"ok": true, "samples": int, "sample_rate": int} | {"ok": false, "error": str}
Samples are written to `out` as little-endian float32.
Special request {"cmd": "voices"} returns {"ok": true, "voices": [[id, name, locale, sr], ...]}.
"""
import json
import sys

import numpy as np
from mediapipe.tasks.python.audio import text_to_speech as T
from mediapipe.tasks.python.core import base_options as B

model = sys.argv[1]
engines = {}


def engine(rate):
    key = round(float(rate), 3)
    if key not in engines:
        for e in engines.values():
            e.close()
        engines.clear()
        engines[key] = T.TextToSpeech.create_from_options(
            T.TextToSpeechOptions(base_options=B.BaseOptions(model_asset_path=model), speaking_rate=key)
        )
    return engines[key]


def reply(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


reply({"ok": True, "ready": True})
for line in sys.stdin:
    try:
        req = json.loads(line)
        if req.get("cmd") == "voices":
            vs = engine(1.0).list_voices()
            reply({"ok": True, "voices": [[v.voice_id, v.display_name, v.locale, v.sample_rate_hz] for v in vs]})
            continue
        kwargs = {"voice_id": req["voice"]}
        if req.get("locale"):
            kwargs["locale"] = req["locale"]
        r = engine(req.get("rate", 1.0)).synthesize(req["text"], **kwargs)
        s = np.asarray(r.samples, dtype="<f4")
        s.tofile(req["out"])
        reply({"ok": True, "samples": int(s.size), "sample_rate": int(r.sample_rate_hz)})
    except Exception as e:  # keep serving
        reply({"ok": False, "error": repr(e)})

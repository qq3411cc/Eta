# Offline ASR attribution and binary provenance

## Adapted source
ZorvAI — Copyright 2025-2026 ZorvAI. Apache License 2.0.
Reference checkout: https://github.com/Quor-a/ZorvAI (confirmed against pinned local checkout origin).
Pinned source commit: 036e4cdc4e0d5c8aa3e8dd192aa4ddf441984e24.
Relevant source paths: app/src/main/java/com/k2fsa/sherpa/ncnn/SherpaNcnn.kt;
app/src/main/java/com/ai/assistance/quro/core/tools/QuroAsrService.kt and QuroOnDeviceAsr.kt.
Eta modifications: preserve JNI package/field/signature ABI; remove flavor coupling and finalizer;
retain explicit worker-owned release; replace whole-recording IPC with bounded streaming ACK protocol;
add explicit app-private atomic manifest-verified download/import, death/cancel/timeouts, single-result callbacks.
No other ZorvAI runtime, GPL components, app configuration, credentials or user audio is incorporated.
The applicable Apache-2.0 license is included in LICENSE-APACHE-2.0.txt.

## Precompiled native artifacts (not compiled by this integration)
Source: user-provided ZorvAI-v1.2.4.apk, only its lib/arm64-v8a entries below.
The APK SHA256 audited before integration is 924f3a31aee7861bc320a5fbae3b556790934c3080218fda2a6e65db4b888374.
- libsherpa-ncnn-jni.so: 892600 bytes; SHA256 b029ace14714523d8b701df11dd66f76c2f6c1213cbf8b0494c741c2a9f0612b.
- libncnn.so: 4581184 bytes; SHA256 6f2a42d3acb6a5dd1677e458763bb45854d92efe036fd641a7cf5718d56c93d1.
Sherpa-NCNN / k2-fsa contributors: Apache-2.0, https://github.com/k2-fsa/sherpa-ncnn.
NCNN: Copyright (C) 2017 Tencent. All rights reserved. BSD-3-Clause,
https://github.com/Tencent/ncnn. Full NCNN copyright/license and listed dependency notices
are retained in LICENSE-NCNN.txt; Sherpa license is in LICENSE-SHERPA-NCNN.txt.
Exact native upstream commit, compiler flags and complete static dependency build provenance
were not supplied by this precompiled APK and are not asserted/reconstructed here.
These libraries implement ASR, not a TTS synthesis backend. Greedy only; no beam support claim.

## Optional downloadable/imported model
sherpa-ncnn-streaming-zipformer-small-bilingual-zh-en-2023-02-16.
Seven model files total 53,023,752 bytes, individually pinned in manifest.json.
Original model README is preserved byte-for-byte in default_small/README.md
(SHA256 fcfb59325916e745026dcd0c1f35d0ab1e71fc11342c3da2191aa0167ba63d83).
Model card declares Apache-2.0; full license is default_small/LICENSE.
Model distribution: https://huggingface.co/csukuangfj/sherpa-ncnn-streaming-zipformer-small-bilingual-zh-en-2023-02-16.
README credits torchscript source https://huggingface.co/pfluo/k2fsa-zipformer-bilingual-zh-en-t
and training code https://github.com/k2-fsa/icefall/tree/master/egs/librispeech/ASR/pruned_transducer_stateless7_streaming.
Copied only the seven audited model files and README from the explicitly authorized existing deployment;
no test WAV, audio, log, preference, API key, alternate encoder or other private file was read/copied.
Full license text was added from the reference source Apache-2.0 license; the upstream directory did not contain a separate LICENSE in the audited whitelist.

## APK distribution update
No model payloads (BIN/PARAM/tokens) are bundled. Only catalog, unchanged per-model manifests, pinned sources and licenses remain. Explicit user download/import is required on fresh installs. Native libraries remain bundled, unchanged. Existing deployed directories and markers remain supported. Small source revision: 5fb2fb06fe78a776dbda78226c42d435359064f7; Standard: 05945efc40afe4b572542f01104ca5c413a9f6e1. All seven Small files were compared to official upstream metadata (LFS SHA256 or Git blob SHA1), without downloading weights.

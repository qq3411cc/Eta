# Qwen3-ASR backend attribution and provenance

Eta's optional Qwen3-ASR-0.6B backend uses sherpa-onnx v2.0.1, commit
`af2d8d81dbfa61c4cea4c568da78f3982edca005`, k2-fsa contributors (Apache-2.0).
Source: https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.1
The Kotlin offline wrapper retains the original package, JNI signatures, config
and result fields. Eta omits only the unrelated example-model catalog following
the recognizer class. Three prerequisite config files are unmodified. Eta's
application-specific service, pipe protocol, lifecycle and model installer are
new integration code. See LICENSE-SHERPA-ONNX.txt.

Prebuilt ARM64 JNI/runtime are from the same official release:
https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-v2.0.1-android.tar.bz2
Archive SHA256: 39003753ec293425ed56723ea9b972b98841a490af3311670c3060548d654330.
Only libsherpa-onnx-jni.so and libonnxruntime.so are bundled. The latter identifies
itself as ONNX Runtime 1.28.2 and is copyright Microsoft Corporation, MIT license;
see LICENSE-ONNXRUNTIME.txt and ThirdPartyNotices-ONNXRUNTIME.txt from that tag.
No standalone CLI or unrelated engine libraries are included.

The original Qwen3-ASR-0.6B model is by the Qwen team / Alibaba Cloud:
https://huggingface.co/Qwen/Qwen3-ASR-0.6B
Its pinned inspected model card at revision
`5eb144179a02acc5e5ba31e748d22b0cf3e303b0` declares Apache-2.0.
The original model repository did not list a separate LICENSE file. The Apache
license text supplied as LICENSE-QWEN.txt is from the official Qwen3-ASR code
repository https://github.com/QwenLM/Qwen3-ASR/blob/main/LICENSE (the source URL
and actual downloaded hash are recorded in license-sources.json); it is not
misrepresented as a LICENSE extracted from the model distribution.

The supported ONNX conversion is NOT claimed to have been published by the
Qwen team. The official k2-fsa model release README credits conversion author
Wasser1462, source/export scripts:
https://github.com/Wasser1462/Qwen3-ASR-onnx
https://modelscope.cn/models/zengshuishui/Qwen3-ASR-onnx/files
The original distribution README is preserved in UPSTREAM-MODEL-README.md.
Trusted delivery is the fixed k2-fsa release archive named in manifest.json;
per-file provenance also points to the pinned csukuangfj2 HF revision there.

The complete official ONNX archive contains NO LICENSE or NOTICE files.
The original Qwen model's Apache-2.0 declaration and the engine/runtime licenses
were checked separately. A conversion-specific license/notice was not found;
this provenance caveat is preserved, not treated as completed legal clearance
or a claim that all derivative-distribution licensing has been resolved.

Model weights and test audio are NOT embedded in this application. Download is
optional, explicit, local and checksum-validated. Raw .safetensors are not a
supported runtime format and renaming them does not bypass the six-file hashes.

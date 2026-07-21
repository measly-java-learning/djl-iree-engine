# MobileNetV2 Example & Benchmark

Runs MobileNetV2 `[1,3,224,224] → [1,1000]` through this IREE engine

## Prerequisites

- **`uv`** on `PATH` (used to export the models; it self-provisions the pinned
  torch/torchvision/iree-turbine via PEP 723 inline metadata in `tools/scripts/export_mobilenet.py`).
- Network on first run: `uv` downloads the export deps

> **`uv` fallback:** if torch wheels misbehave under inline script metadata (index URLs / CPU-only
> variants), create a `uv` project or venv from the same pins in the script header and run
> `python tools/scripts/export_mobilenet.py` inside it. The pins in the script stay the source of truth.

## Generate the model artifacts (once)

    ./gradlew :example:exportModels

Writes `mobilenet_v2.mlir`, `mobilenet_v2.vmfb`, and `versions.json` into `example/build/models/`.
Nothing large is committed to git.
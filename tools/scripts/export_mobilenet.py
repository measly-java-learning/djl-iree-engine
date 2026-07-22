# /// script
# requires-python = ">=3.10,<3.13"
# dependencies = [
#   "torch==2.12.1",
#   "torchvision==0.27.1",
#   "iree-turbine==3.9.0",
# ]
#
# [tool.uv.sources]
# torch = { index = "pytorch-cpu" }
# torchvision = { index = "pytorch-cpu" }
#
# [[tool.uv.index]]
# name = "pytorch-cpu"
# url = "https://download.pytorch.org/whl/cpu"
# explicit = true
# ///
"""Export MobileNetV2 to IREE

Run with uv so the pinned deps are provisioned automatically:

    uv run tools/scripts/export_mobilenet.py

Writes into the current working directory:
  - mobilenet_v2.XXX
  - versions.json     ({torch, torchvision, iree} for reproducibility)

The `[tool.uv]` index override pulls torch/torchvision from the CPU-only wheel index
(download.pytorch.org/whl/cpu) so this script doesn't drag in multi-GB CUDA dependencies.

TODO(ABI enforcement): the `.vmfb` here is produced by iree-turbine 3.9.0's *bundled*
iree-compile, NOT the iree-base-compiler 3.11.0 that the iree-runtime-dist pairing
contract names for the linked runtime (runtime commit e4a3b040, vm_bytecode_version in
its manifest.json). It currently loads because the VM bytecode / HAL import ABI happened
to stay compatible across 3.9 -> 3.11 — that compatibility is coincidental and unenforced,
and is exactly the version-skew class that broke loading during the skeleton build
(`hal version mismatch; have 6 but require 7`). Before relying on this for more models or
across a dist bump, enforce the ABI: either export with iree-base-compiler==3.11.0 to honor
the contract, or assert the produced module's vm_bytecode_version against the dist manifest
and fail the export on mismatch. Tracked for manual follow-up.
"""
import json
from importlib.metadata import PackageNotFoundError, version

import torch
import torchvision

from iree.compiler import compile_file
from iree.turbine import aot


"""
TYPE_MAPPING, DEFAULT_INPUT_SHAPE, and parse_input_shape_and_type come from this issue:
https://github.com/iree-org/iree/issues/22674
The default way of creating example inputs failed inside Dynamo
`example = (torch.randn(1, 3, 224, 224),)`
"""
TYPE_MAPPING = {
    "f32": torch.float32,
    "f16": torch.float16,
}
DEFAULT_INPUT_SHAPE = "1x3x224x224xf32"


def parse_input_shape_and_type(txt):
    fields = txt.split("x")
    return tuple(int(x) for x in fields[:-1]), TYPE_MAPPING[fields[-1]]


def _v(pkg: str) -> str:
    try:
        return version(pkg)
    except PackageNotFoundError:
        return "unknown"


def main() -> None:
    weights = torchvision.models.MobileNet_V2_Weights.DEFAULT
    model = torchvision.models.mobilenet_v2(weights=weights).eval()
    input_shape, input_type = parse_input_shape_and_type(DEFAULT_INPUT_SHAPE)
    example = torch.randn(input_shape, dtype=input_type)

    # This fails, but the IREE GitHub repo has what looks to be a valid export script from 3.8.0
    # https://github.com/iree-org/iree/issues/22674
    # However it uses `transformers` to get the model & config
    exported = aot.export(model, example)
    mlir_path = "mobilenet_v2.mlir"
    exported.save_mlir(mlir_path)
    compile_file(mlir_path, output_file="mobilenet_v2.vmfb", target_backends=["llvm-cpu"], extra_args=["--iree-llvmcpu-target-cpu=host"])

    with open("versions.json", "w") as f:
        json.dump(
            {
                "torch": _v("torch"),
                "torchvision": _v("torchvision"),
            },
            f,
            indent=2,
        )

    print("wrote mobilenet_v2.mlir, mobilenet_v2.vmfb, versions.json")


if __name__ == "__main__":
    main()

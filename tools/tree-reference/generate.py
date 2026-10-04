#!/usr/bin/env python3
"""Regenerate independent fixtures using rustc, without Cargo or network access."""
from pathlib import Path
import subprocess
import tempfile

here = Path(__file__).resolve().parent
with tempfile.TemporaryDirectory(prefix="fastblake-tree-reference-") as directory:
    source = Path(directory) / "main.rs"
    binary = Path(directory) / "reference"
    source.write_bytes((here / "reference_impl.rs").read_bytes() + b"\n" +
                      (here / "harness.rs").read_bytes())
    subprocess.run(["rustc", "--edition=2021", "-O", str(source), "-o", str(binary)], check=True)
    fixtures = subprocess.check_output([str(binary)])
    (here.parent.parent / "src/test/resources/tree-reference.tsv").write_bytes(fixtures)

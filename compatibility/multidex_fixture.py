"""Generate fixture bundles with deterministic DEX placement using Android build-tools."""

import argparse
from copy import copy
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory
from zipfile import ZipFile

ENTRY = "com/xlythe/test/extension/Extension.class"
VIEW = "com/xlythe/test/extension/Label.class"


def classes(dexdump, dex):
    dump = subprocess.check_output([dexdump, dex], text=True)
    return {name + ".class" for name in re.findall(r"Class descriptor\s+: 'L([^']+);'", dump)}


def partition(tools, source, secondary, output):
    output.mkdir()
    contents = classes(tools / "dexdump", source)
    if not secondary <= contents:
        raise ValueError(f"Missing fixture classes: {secondary - contents}")
    keep = output / "main-dex-list"
    keep.write_text("\n".join(sorted(contents - secondary)) + "\n")
    subprocess.run([tools / "d8", "--min-api", "14", "--debug", "--main-dex-list",
                    keep, "--output", output, source], check=True)
    first, second = output / "classes.dex", output / "classes2.dex"
    if classes(tools / "dexdump", first) != contents - secondary or classes(tools / "dexdump", second) != secondary:
        raise ValueError("D8 did not produce the requested DEX placement")
    return first, second


def write_bundle(source, output, dex_files):
    with ZipFile(output, "w") as bundle:
        for member in source.infolist():
            if not member.filename.startswith(("base/dex/", "META-INF/")):
                bundle.writestr(copy(member), source.read(member))
        for index, dex in enumerate(dex_files, 1):
            name = "classes.dex" if index == 1 else f"classes{index}.dex"
            bundle.write(dex, f"base/dex/{name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build_tools", type=Path)
    parser.add_argument("fixture_bundle", type=Path)
    parser.add_argument("output_dir", type=Path)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    with ZipFile(args.fixture_bundle) as bundle, TemporaryDirectory() as temp:
        if [name for name in bundle.namelist() if name.startswith("base/dex/")] != ["base/dex/classes.dex"]:
            raise ValueError("Expected the original single-DEX fixture")
        work = Path(temp)
        original = work / "classes.dex"
        original.write_bytes(bundle.read("base/dex/classes.dex"))
        primary, view = partition(args.build_tools, original, {VIEW}, work / "view")
        write_bundle(bundle, args.output_dir / "secondary-view.aab", [primary, view])
        primary, both = partition(args.build_tools, original, {ENTRY, VIEW}, work / "both")
        entry, view = partition(args.build_tools, both, {VIEW}, work / "entry")
        write_bundle(bundle, args.output_dir / "secondary-entry.aab", [primary, entry, view])


if __name__ == "__main__":
    main()

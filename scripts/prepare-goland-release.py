"""Validate the built plugin's identity before staging a versioned release ZIP."""

import argparse
import hashlib
import io
from pathlib import Path, PurePosixPath
import re
import sys
import xml.etree.ElementTree as ET
from zipfile import BadZipFile, ZipFile


def prepare_release(distributions: Path, output: Path, version: str) -> Path:
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version):
        raise ValueError("Release version must be MAJOR.MINOR.PATCH without leading zeroes")
    candidates = sorted(distributions.glob("*.zip"))
    if len(candidates) != 1 or candidates[0].is_symlink() or not candidates[0].is_file():
        raise ValueError("Expected exactly one regular, non-symlink plugin ZIP")
    payload = candidates[0].read_bytes()
    descriptors = []
    roots = set()
    with ZipFile(io.BytesIO(payload)) as archive:
        names = archive.namelist()
        if not names or len(names) != len(set(names)) or archive.testzip() is not None:
            raise ValueError("Plugin ZIP is empty, duplicated or corrupt")
        for entry in archive.infolist():
            path = PurePosixPath(entry.filename)
            if (path.is_absolute() or ".." in path.parts or "\\" in entry.filename
                    or not path.parts or (entry.external_attr >> 16) & 0o170000 == 0o120000):
                raise ValueError("Plugin ZIP contains an unsafe path or symlink")
            roots.add(path.parts[0])
            if len(path.parts) != 3 or path.parts[1] != "lib" or path.suffix != ".jar":
                continue
            with ZipFile(io.BytesIO(archive.read(entry))) as jar:
                metadata = [item for item in jar.infolist() if item.filename == "META-INF/plugin.xml"]
                if len(metadata) > 1 or jar.testzip() is not None:
                    raise ValueError("Plugin JAR has duplicate metadata or corrupt entries")
                if metadata:
                    xml = jar.read(metadata[0])
                    if b"<!DOCTYPE" in xml or b"<!ENTITY" in xml:
                        raise ValueError("Plugin descriptor must not declare entities")
                    descriptors.append(ET.fromstring(xml))
    if len(roots) != 1 or len(descriptors) != 1:
        raise ValueError("Expected one plugin directory and exactly one plugin descriptor")
    descriptor = descriptors[0]
    if (descriptor.tag != "idea-plugin" or descriptor.findtext("id", "").strip() != "com.reqws.workspace"
            or descriptor.findtext("version", "").strip() != version):
        raise ValueError("Plugin ID or embedded version does not match the release")
    output.mkdir(parents=True, exist_ok=True)
    target = output / f"ReqWS-{version}-goland-plugin.zip"
    checksum = target.with_suffix(".zip.sha256")
    if target.exists() or checksum.exists():
        raise ValueError("Refusing to replace existing staged release files")
    # Stage exactly the bytes whose descriptor and archive integrity were checked above.
    with target.open("xb") as stream:
        stream.write(payload)
    with checksum.open("x", encoding="utf-8") as stream:
        stream.write(f"{hashlib.sha256(payload).hexdigest()}  {target.name}\n")
    return target


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--distributions", type=Path, default=Path("integrations/goland/build/distributions"))
    parser.add_argument("--output", type=Path, default=Path("dist/release"))
    args = parser.parse_args()
    try:
        print(prepare_release(args.distributions, args.output, args.version))
    except (OSError, ValueError, BadZipFile, ET.ParseError) as error:
        print(f"Plugin release validation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

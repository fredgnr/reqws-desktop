"""Read-only validation shared by staging and Marketplace submission."""

import hashlib
import io
from pathlib import Path, PurePosixPath
import re
import stat
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from ide_compatibility import check_descriptor

XML_ID = 'com.reqws.workspace'
VERSION_PATTERN = r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)'
MAX_ARCHIVE_BYTES = 400_000_000
MAX_EXPANDED_BYTES = 800_000_000


def validate_version(version):
    if not re.fullmatch(VERSION_PATTERN, version):
        raise ValueError('Version must be MAJOR.MINOR.PATCH without leading zeroes')
    return version


def regular_file(path):
    path = Path(path).absolute()
    if any(parent.is_symlink() for parent in (path, *path.parents)) or not path.is_file():
        raise ValueError('Expected a regular file without symlink path components')
    return path


def checked_entries(archive):
    entries = archive.infolist()
    names = [entry.filename for entry in entries]
    if not entries or len(names) != len(set(names)) or len(entries) > 100_000:
        raise ValueError('Archive is empty or has duplicate/excessive entries')
    if sum(entry.file_size for entry in entries) > MAX_EXPANDED_BYTES:
        raise ValueError('Expanded archive exceeds the size limit')
    for entry in entries:
        name = entry.filename.rstrip('/')
        path = PurePosixPath(name)
        if (not name or name != path.as_posix() or path.is_absolute()
                or '..' in path.parts or '\\' in name or ':' in name
                or any(ord(char) < 32 for char in name)
                or stat.S_ISLNK(entry.external_attr >> 16) or entry.flag_bits & 1):
            raise ValueError('Archive contains an unsafe path, symlink or encrypted entry')
    if archive.testzip() is not None:
        raise ValueError('Archive contains corrupt entries')
    return entries


def validate_plugin(path, version, *, historical=False):
    validate_version(version)
    path = regular_file(path)
    if not 0 < path.stat().st_size <= MAX_ARCHIVE_BYTES:
        raise ValueError('Plugin archive size is invalid')
    payload = path.read_bytes()
    descriptors, icons, roots = [], [], set()
    with ZipFile(io.BytesIO(payload)) as archive:
        for entry in checked_entries(archive):
            member = PurePosixPath(entry.filename)
            roots.add(member.parts[0])
            if entry.filename.endswith('META-INF/plugin.xml'):
                raise ValueError('Unexpected loose plugin descriptor')
            if member.suffix != '.jar':
                continue
            with ZipFile(io.BytesIO(archive.read(entry))) as jar:
                for item in checked_entries(jar):
                    if item.filename == 'META-INF/plugin.xml':
                        if len(member.parts) != 3 or member.parts[1] != 'lib':
                            raise ValueError('Main descriptor must be in the top-level lib JAR')
                        if item.file_size > 1_000_000:
                            raise ValueError('Plugin descriptor is too large')
                        xml = jar.read(item).decode('utf-8')
                        if re.search(r'<!\s*(DOCTYPE|ENTITY)', xml, re.IGNORECASE):
                            raise ValueError('Plugin descriptor must not declare entities')
                        descriptors.append(ET.fromstring(xml))
                    elif item.filename == 'META-INF/pluginIcon.svg':
                        icons.append(jar.read(item))
    if len(roots) != 1 or len(descriptors) != 1:
        raise ValueError('Expected one plugin directory and exactly one descriptor')
    descriptor = descriptors[0]
    for tag, expected in [('id', XML_ID), ('name', 'ReqWS'), ('version', version)]:
        nodes = descriptor.findall(tag)
        if len(nodes) != 1 or (nodes[0].text or '').strip() != expected:
            raise ValueError('Plugin identity does not match the release')
    if descriptor.tag != 'idea-plugin':
        raise ValueError('Invalid plugin descriptor root')
    bounds = descriptor.findall('idea-version')
    if historical:
        if len(bounds) != 1:
            raise ValueError('Missing historical compatibility descriptor')
    else:
        check_descriptor(descriptor)
    vendor = descriptor.findall('vendor')
    if (len(vendor) != 1 or (vendor[0].text or '').strip() != 'fredgnr'
            or vendor[0].get('email') != 'z513317651@gmail.com'
            or vendor[0].get('url') != 'https://github.com/fredgnr/reqws-desktop'):
        raise ValueError('Plugin vendor metadata is incomplete')
    for field in ['description', 'change-notes']:
        nodes = descriptor.findall(field)
        value = ''.join(nodes[0].itertext()).strip() if len(nodes) == 1 else ''
        if not value or re.search(r'\b(TODO|TBD|placeholder)\b', value, re.IGNORECASE):
            raise ValueError('Plugin description/change notes are missing or placeholders')
    if len(icons) != 1:
        raise ValueError('Expected one plugin icon')
    icon = ET.fromstring(icons[0])
    if icon.tag != '{http://www.w3.org/2000/svg}svg' or icon.get('viewBox') != '0 0 40 40':
        raise ValueError('Expected a 40 by 40 SVG plugin icon')
    return {'version': version, 'xmlId': XML_ID, 'sha256': hashlib.sha256(payload).hexdigest(),
            'size': len(payload), 'since': bounds[0].get('since-build'), 'until': bounds[0].get('until-build')}


def plugin_checksum(manifest, filename):
    """Validate the plugin record without requiring the other Release assets locally."""
    if len(manifest) > 16_384:
        raise ValueError('Checksum manifest is too large')
    found = []
    for line in manifest.decode('ascii').splitlines():
        match = re.fullmatch(r'([0-9a-f]{64})  ([A-Za-z0-9._-]+)', line)
        if not match:
            raise ValueError('Nonstandard checksum record')
        if match[2] == filename:
            found.append(match[1])
    if len(found) != 1:
        raise ValueError('Expected one checksum record for the exact plugin filename')
    return found[0]

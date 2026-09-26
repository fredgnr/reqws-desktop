"""Fail-closed audit of the single approved initial-JPS synchronization API use.

This is a build/report policy, never a production dependency. Verifier reports
remain untouched. The bytecode check applies to the actual candidate, including
all nested JARs, and does not grant an exception to an entire class or package.
"""

import argparse
from collections import Counter
import io
from pathlib import Path
import re
import struct
from zipfile import ZipFile

from plugin_release import checked_entries

WRAPPER = 'com/reqws/goland/loading/model/InitialJpsSynchronization'
CALLER = 'awaitPlatform'
CALLER_DESCRIPTOR = '(Lcom/intellij/openapi/project/Project;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;'
OWNER = 'com/intellij/platform/backend/workspace/impl/WorkspaceModelInternal'
MEMBER = 'awaitSynchronizationWithJpsModel'
DESCRIPTOR = '(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;'
CAST_MESSAGE = 'null cannot be cast to non-null type ' + OWNER.replace('/', '.')
TOKENS = (b'WorkspaceModelInternal', MEMBER.encode())
EXCEPTION_ID = 'initial-jps-synchronization'


class ClassReader:
    def __init__(self, data):
        self.data, self.offset = data, 0

    def take(self, size):
        if size < 0 or self.offset + size > len(self.data):
            raise ValueError('Truncated JVM class data')
        result = self.data[self.offset:self.offset + size]
        self.offset += size
        return result

    def number(self, size):
        return int.from_bytes(self.take(size), 'big')

    def end(self):
        if self.offset != len(self.data):
            raise ValueError('Unexpected trailing JVM class data')


class ClassFile:
    def __init__(self, data):
        reader = ClassReader(data)
        if reader.take(4) != b'\xca\xfe\xba\xbe':
            raise ValueError('Invalid JVM class header')
        reader.take(4)  # minor/major; the pinned compiler and Verifier enforce compatibility.
        self.pool = [None]
        count = reader.number(2)
        while len(self.pool) < count:
            tag = reader.number(1)
            if tag == 1:
                # Policy identifiers are ASCII; preserve every byte of modified
                # UTF-8 metadata without normalizing or replacing invalid text.
                value = reader.take(reader.number(2)).decode('latin1')
            elif tag in (3, 4):
                value = reader.take(4)
            elif tag in (5, 6):
                value = reader.take(8)
            elif tag in (7, 8, 16, 19, 20):
                value = reader.number(2)
            elif tag in (9, 10, 11, 12, 17, 18):
                value = (reader.number(2), reader.number(2))
            elif tag == 15:
                value = (reader.number(1), reader.number(2))
            else:
                raise ValueError(f'Unknown JVM constant tag {tag}')
            self.pool.append((tag, value))
            if tag in (5, 6):
                self.pool.append(None)
        if len(self.pool) != count:
            raise ValueError('Invalid JVM constant pool size')
        self.access = reader.number(2)
        self.name = self.class_name(reader.number(2))
        self.superclass = reader.number(2)
        self.interfaces = [reader.number(2) for _ in range(reader.number(2))]
        self.fields = self.members(reader)
        self.methods = self.members(reader)
        self.attributes = self.attributes_from(reader)
        reader.end()

    def constant(self, index, tags):
        if not 0 < index < len(self.pool) or self.pool[index] is None or self.pool[index][0] not in tags:
            raise ValueError('Invalid JVM constant reference')
        return self.pool[index][1]

    def text(self, index):
        return self.constant(index, (1,))

    def class_name(self, index):
        return self.text(self.constant(index, (7,)))

    def member_reference(self, index):
        owner, signature = self.constant(index, (9, 10, 11))
        name, descriptor = self.constant(signature, (12,))
        return self.class_name(owner), self.text(name), self.text(descriptor)

    def attributes_from(self, reader):
        return [(self.text(reader.number(2)), reader.take(reader.number(4)))
                for _ in range(reader.number(2))]

    def members(self, reader):
        return [(reader.number(2), self.text(reader.number(2)), self.text(reader.number(2)), self.attributes_from(reader))
                for _ in range(reader.number(2))]


def instructions(code):
    """Decode JVM instruction boundaries, including switch padding and wide."""
    reader = ClassReader(code)
    sizes = {**dict.fromkeys((16, 18, *range(21, 26), *range(54, 59), 169, 188), 1),
             **dict.fromkeys((17, 19, 20, 132, *range(153, 169), *range(178, 185),
                             187, 189, 192, 193, 198, 199), 2),
             185: 4, 186: 4, 197: 3, 200: 4, 201: 4}
    while reader.offset < len(code):
        offset, opcode = reader.offset, reader.number(1)
        if opcode > 201:
            raise ValueError(f'Unsupported JVM opcode {opcode}')
        if opcode in (170, 171):
            reader.take((-reader.offset) % 4)
            reader.take(4)
            if opcode == 170:
                low, high = struct.unpack('>ii', reader.take(8))
                if high < low:
                    raise ValueError('Invalid JVM tableswitch')
                reader.take((high - low + 1) * 4)
            else:
                count = struct.unpack('>i', reader.take(4))[0]
                reader.take(count * 8)
            yield offset, opcode, None
            continue
        if opcode == 196:
            wide = reader.number(1)
            if wide not in (*range(21, 26), *range(54, 59), 132, 169):
                raise ValueError('Invalid JVM wide instruction')
            reader.take(4 if wide == 132 else 2)
            yield offset, opcode, None
            continue
        operands = reader.take(sizes.get(opcode, 0))
        index = None
        if opcode == 18:
            index = operands[0]
        elif opcode in (19, 20, *range(178, 190), 192, 193, 197) and opcode != 188:
            index = int.from_bytes(operands[:2], 'big')
        yield offset, opcode, index


def audit_class(data, entry):
    cls = ClassFile(data)
    if entry != cls.name + '.class':
        raise ValueError('JVM class path does not match its declared name')
    if not any(token in data for token in TOKENS):
        return cls.name, False
    if cls.name != WRAPPER:
        raise ValueError(f'Initial JPS API reference outside the approved wrapper: {cls.name}')
    if any(cls.class_name(index) == OWNER for index in [cls.superclass, *cls.interfaces] if index):
        raise ValueError('Initial JPS API must not be a supertype')
    class_constants, member_constants, diagnostic_constants = set(), set(), set()
    for index, item in enumerate(cls.pool):
        if item is None:
            continue
        tag, value = item
        if tag == 1 and any(token.decode() in value for token in TOKENS) and value not in (OWNER, MEMBER, CAST_MESSAGE):
            raise ValueError('Unapproved initial JPS type, signature, metadata or string reference')
        if tag == 7 and cls.class_name(index) == OWNER:
            class_constants.add(index)
        if tag in (9, 10, 11) and cls.member_reference(index)[0] == OWNER:
            if tag != 11 or cls.member_reference(index) != (OWNER, MEMBER, DESCRIPTOR):
                raise ValueError('Unapproved initial JPS member reference')
            member_constants.add(index)
        if tag == 8 and cls.text(value) == CAST_MESSAGE:
            diagnostic_constants.add(index)
    if len(class_constants) != 1 or len(member_constants) != 1 or len(diagnostic_constants) != 1:
        raise ValueError('Expected exactly one initial JPS class and method constant')
    for item in cls.pool:
        if item is None:
            continue
        tag, value = item
        if tag == 15 and value[1] in member_constants:
            raise ValueError('Initial JPS API must not be a method handle')
        if tag == 8 and cls.text(value) in (OWNER, MEMBER):
            raise ValueError('Initial JPS API must not be a string constant')
    for attribute, payload in cls.attributes:
        if attribute == 'BootstrapMethods':
            reader = ClassReader(payload)
            for _ in range(reader.number(2)):
                handle = reader.number(2)
                arguments = [reader.number(2) for _ in range(reader.number(2))]
                if {handle, *arguments} & (class_constants | member_constants | diagnostic_constants):
                    raise ValueError('Initial JPS API must not be a bootstrap argument')
            reader.end()
    found = Counter()
    for access, name, descriptor, attributes in cls.methods:
        for attribute, payload in attributes:
            if attribute != 'Code':
                continue
            reader = ClassReader(payload)
            reader.take(4)
            code = reader.take(reader.number(4))
            for _, opcode, index in instructions(code):
                if index not in class_constants | member_constants | diagnostic_constants:
                    continue
                if (name, descriptor) != (CALLER, CALLER_DESCRIPTOR) or not access & 0x0002:
                    raise ValueError('Initial JPS API instruction outside the approved private method')
                if opcode == 192 and index in class_constants:
                    found['cast'] += 1
                elif opcode == 185 and index in member_constants:
                    found['invoke'] += 1
                elif opcode in (18, 19) and index in diagnostic_constants:
                    found['cast-diagnostic'] += 1
                else:
                    raise ValueError('Unapproved initial JPS API instruction')
            for _ in range(reader.number(2)):
                reader.take(6)
                if reader.number(2) in class_constants:
                    raise ValueError('Initial JPS type must not be an exception handler')
            cls.attributes_from(reader)
            reader.end()
    if found != Counter(cast=1, invoke=1, **{'cast-diagnostic': 1}):
        raise ValueError('Expected one initial JPS cast and one invocation')
    return cls.name, True


def audit_jars(jars):
    seen, approved = set(), 0
    for label, data in jars:
        with ZipFile(io.BytesIO(data)) as jar:
            for entry in checked_entries(jar):
                if not entry.filename.endswith('.class'):
                    continue
                name, contains_exception = audit_class(jar.read(entry), entry.filename)
                if name in seen:
                    raise ValueError(f'Duplicate candidate class: {name}')
                seen.add(name)
                approved += contains_exception
    if not seen:
        raise ValueError('Candidate contains no JVM classes')
    return {'id': EXCEPTION_ID, 'present': bool(approved), 'classCount': len(seen)}


def audit_archive(path):
    with ZipFile(path) as archive:
        jars = [(entry.filename, archive.read(entry)) for entry in checked_entries(archive)
                if entry.filename.endswith('.jar')]
    return audit_jars(jars)


def audit_sources(root):
    root = Path(root)
    wrapper = root / 'kotlin' / (WRAPPER + '.kt')
    for path in root.rglob('*'):
        if path.is_file() and any(token in path.read_bytes() for token in TOKENS) and path != wrapper:
            raise ValueError(f'Initial JPS API source reference outside the approved wrapper: {path}')


# Observed with Verifier 1.410 against GO 262.8665.270, from the compiled
# InitialJpsSynchronization.awaitPlatform method. Full lines, never substrings
# or package patterns: any new usage, caller, signature or format fails closed.
API_SIGNATURE = (OWNER.replace('/', '.') + '.' + MEMBER
                 + '(kotlin.coroutines.Continuation arg0) : java.lang.Object')
USAGE_LOCATION = WRAPPER.replace('/', '.') + '.awaitPlatform(Project, Continuation) : Object'
INTERNAL_SUFFIX = (' is marked with @org.jetbrains.annotations.ApiStatus.Internal annotation or '
                   '@com.intellij.openapi.util.IntellijInternalApi annotation and indicates that the ')
APPROVED_REPORTS = {
    'internal-api-usages.txt': frozenset({
        'Internal method ' + API_SIGNATURE + ' is invoked in ' + USAGE_LOCATION + '. This method'
        + INTERNAL_SUFFIX + 'method is not supposed to be used in client code.',
        'Internal interface ' + OWNER.replace('/', '.') + ' is referenced in ' + USAGE_LOCATION + '. This interface'
        + INTERNAL_SUFFIX + 'class is not supposed to be used in client code.',
    }),
    'experimental-api-usages.txt': frozenset({
        'Experimental API method ' + API_SIGNATURE + ' is invoked in ' + USAGE_LOCATION
        + '. This method can be changed in a future release leading to incompatibilities',
    }),
}
APPROVED_VERDICT = 'Compatible. 1 usage of experimental API. 2 usages of internal API'


def approved_reports(directory, audit):
    """Return the exact observed exception categories, or reject unknown content."""
    categories = set()
    for filename, expected in APPROVED_REPORTS.items():
        path = Path(directory) / filename
        if not path.is_file() or not path.read_text().strip():
            continue
        lines = path.read_text().splitlines()
        if not audit or not audit.get('present') or not expected or Counter(lines) != Counter(expected):
            raise ValueError(f'Unapproved API usage in {filename}')
        categories.add('INTERNAL_API_USAGES' if filename.startswith('internal-') else 'EXPERIMENTAL_API_USAGES')
    if categories and len(categories) != len(APPROVED_REPORTS):
        raise ValueError('Incomplete initial JPS API exception reports')
    return categories


def approved_gradle_failure(log, returncode, categories):
    """Do not turn an unrelated failure or cancellation into a policy exception."""
    markers = re.findall(r'Verification failed with \[([A-Z_, ]+)\] problems\. See the report at:', log)
    failures = {frozenset(part.strip() for part in marker.split(',')) for marker in markers}
    tasks = set(re.findall(r"Execution failed for task '([^']+)'", log))
    failed_tasks = set(re.findall(r'^> Task (\S+) FAILED$', log, re.MULTILINE))
    return (returncode == 1 and bool(categories) and failures == {frozenset(categories)}
            and tasks == {':verifyPlugin'} and failed_tasks == {':verifyPlugin'}
            and len(re.findall(r'^FAILURE: Build failed with an exception\.$', log, re.MULTILINE)) == 1
            and len(re.findall(r'^BUILD FAILED in .+$', log, re.MULTILINE)) == 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True, type=Path)
    parser.add_argument('--sources', type=Path)
    args = parser.parse_args()
    if args.sources:
        audit_sources(args.sources)
    result = audit_jars([(args.jar.name, args.jar.read_bytes())])
    print(f"Initial JPS API audit passed: {result['classCount']} classes; exception present={result['present']}")


if __name__ == '__main__':
    main()

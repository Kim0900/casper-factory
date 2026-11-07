"""Separate D8 throwing framework stubs from receiver application dependencies."""
import hashlib
import struct
import zipfile


class Dex:
    def __init__(self, data):
        self.data = data
        assert data[:4] == b'dex\n'

    def u32(self, offset):
        return struct.unpack_from('<I', self.data, offset)[0]

    def u16(self, offset):
        return struct.unpack_from('<H', self.data, offset)[0]

    def leb(self, offset):
        value = shift = 0
        while True:
            byte = self.data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
            shift += 7
            assert shift < 35

    def string(self, index):
        _, offset = self.leb(self.u32(self.u32(60) + 4 * index))
        return self.data[offset:self.data.index(0, offset)].decode('utf8', 'replace')

    def type(self, index):
        return self.string(self.u32(self.u32(68) + 4 * index))

    def method(self, index):
        offset = self.u32(92) + 8 * index
        return self.type(self.u16(offset)), self.string(self.u32(offset + 4))

    def classes(self):
        for index in range(self.u32(96)):
            offset = self.u32(100) + 32 * index
            yield self.type(self.u32(offset)), offset

    def verify_throwing_stub(self, name, offset):
        assert self.u32(offset + 4) & 0x1000, (name, 'not synthetic')
        assert self.u32(offset + 16) == 0xffffffff, (name, 'unexpected source')
        position = self.u32(offset + 24)
        counts = []
        for _ in range(4):
            value, position = self.leb(position)
            counts.append(value)
        assert counts == [0, 0, 1, 0], (name, counts)
        method, position = self.leb(position)
        flags, position = self.leb(position)
        code, position = self.leb(position)
        assert self.method(method) == (name, '<clinit>')
        assert flags == 0x11008
        assert self.u16(code) == 1 and self.u16(code + 6) == 0
        assert self.u32(code + 12) == 6
        instructions = struct.unpack_from('<6H', self.data, code + 16)
        assert instructions[0] == 0x0022  # new-instance v0
        assert self.type(instructions[1]) == 'Ljava/lang/NoClassDefFoundError;'
        assert instructions[2] == 0x1070  # invoke-direct {v0}
        assert self.method(instructions[3]) == ('Ljava/lang/NoClassDefFoundError;', '<init>')
        assert instructions[4:] == (0, 0x0027)  # v0, throw v0


def audit_receiver(root, apk):
    forbidden = (b'android/net/Tethering', b'kr/magi/mirror/sender/HotspotMonitor')
    compiled_count = 0
    # Inspect actual compiled project inputs, including annotations/signatures/constant pools.
    for module in ('receiver', 'common'):
        classes = list((root / module / 'build/intermediates/javac/debug').rglob('*.class'))
        assert classes, (module, 'compiled classes missing')
        for path in classes:
            content = path.read_bytes()
            assert not any(token in content for token in forbidden), path
            compiled_count += 1
    stubs = []
    with zipfile.ZipFile(apk) as archive:
        for path in archive.namelist():
            if not path.endswith('.dex'):
                continue
            data = archive.read(path)
            assert forbidden[1] not in data, (path, 'sender implementation in receiver')
            if forbidden[0] not in data:
                continue
            dex = Dex(data)
            # D8 global synthetics may bundle framework stubs even without an app reference.
            # Never exempt a DEX containing project/application code.
            definitions = list(dex.classes())
            assert definitions and b'Lkr/magi/' not in data
            assert all(name.startswith(('Landroid/', 'Lcom/android/tools/r8/'))
                       for name, _ in definitions), (path, 'unexpected stub-container class')
            names = []
            for name, offset in definitions:
                if name.startswith('Landroid/net/Tethering'):
                    dex.verify_throwing_stub(name, offset)
                    names.append(name)
            assert names, (path, 'unresolved tethering reference')
            for index in range(dex.u32(88)):
                owner, method = dex.method(index)
                assert not owner.startswith('Landroid/net/Tethering') or method == '<clinit>'
            for index in range(dex.u32(80)):
                owner = dex.type(dex.u16(dex.u32(84) + 8 * index))
                assert not owner.startswith('Landroid/net/Tethering')
            stubs.append({'dex': path, 'sha256': hashlib.sha256(data).hexdigest(),
                          'throwing_framework_stubs': names})
    return {'compiled_project_classes_checked': compiled_count,
            'project_tethering_references': 0, 'sender_implementation_in_receiver': 0,
            'd8_throwing_stub_containers': stubs,
            'scope': 'static application dependency isolation; not an API27 device execution'}

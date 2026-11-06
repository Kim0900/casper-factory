"""Prove absence of BC's unused EST implementation from runtime inputs and packaged DEX."""
from pathlib import Path
import hashlib
import json
import zipfile

root = Path(__file__).resolve().parents[1]
original = Path((root / 'common/build/isolated-deps/original-path.txt').read_text())
filtered = root / 'common/build/isolated-deps/bcpkix-certificate-only-1.86.jar'
with zipfile.ZipFile(original) as jar:
    removed = sorted(name for name in jar.namelist() if name.startswith('org/bouncycastle/est/') and name.endswith('.class'))
assert removed, 'The original EST implementation must be identified'
with zipfile.ZipFile(filtered) as jar:
    assert not any(name.startswith('org/bouncycastle/est/') for name in jar.namelist())
    assert 'META-INF/LICENSE.md' in jar.namelist()
results = {}
for role in ('sender', 'receiver'):
    apk = root / role / f'build/outputs/apk/debug/{role}-debug-unsigned.apk'
    with zipfile.ZipFile(apk) as archive:
        dex_names = [name for name in archive.namelist() if name.endswith('.dex')]
        assert dex_names
        for name in dex_names:
            assert b'Lorg/bouncycastle/est/' not in archive.read(name), (role, name)
    results[role] = {'bc_est_dex_type_references': 0, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest()}
report = {
    'scope': 'BC_EST_IMPLEMENTATION_ABSENCE_FROM_RUNTIME_CLASSPATH_AND_APK',
    'original_sha256': hashlib.sha256(original.read_bytes()).hexdigest(),
    'filtered_sha256': hashlib.sha256(filtered.read_bytes()).hexdigest(),
    'removed_classes': removed,
    'packaged_est_classes': 0,
    'apks': results,
    'interpretation': 'The removed implementation cannot be called because its types are not packaged. This is not a physical device execution counter.'
}
out = root / 'verification'
out.mkdir(exist_ok=True)
(out / 'bc-runtime-audit.json').write_text(json.dumps(report, indent=2)+'\n')
print(json.dumps({'removed_class_count':len(removed), 'packaged_est_classes':0, 'apks':results}, indent=2))

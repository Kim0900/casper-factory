"""Inspect the actual merged manifests and app-owned sources, without claiming runtime egress proof."""
from pathlib import Path
import hashlib
import json
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
android = '{http://schemas.android.com/apk/res/android}'
allowed = {'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE'}
forbidden = ['MediaProjection', 'AccessibilityService', 'NotificationListenerService',
             'getPrimaryClip', 'dispatchGesture', 'InputManager', 'CarAppService',
             'getExternalStorageDirectory', 'InetAddress.getByName', 'WebView']
result = {'scope': 'synthetic_only_static_inspection', 'apps': {}}
for role in ('sender', 'receiver'):
    manifests = list((root / role / 'build/intermediates/merged_manifests/debug').rglob('AndroidManifest.xml'))
    assert len(manifests) == 1, manifests
    manifest = ET.parse(manifests[0]).getroot()
    permissions = {node.get(android+'name') for node in manifest.findall('uses-permission')}
    assert permissions == allowed, permissions
    application = manifest.find('application')
    assert application.get(android+'allowBackup') == 'false'
    assert application.get(android+'usesCleartextTraffic') == 'false'
    assert not any(application.findall(tag) for tag in ('service', 'receiver', 'provider'))
    assert len(application.findall('activity')) == 1
    assert manifest.find('uses-sdk').get(android+'minSdkVersion') == '24'
    apk = root / role / f'build/outputs/apk/debug/{role}-debug.apk'
    assert apk.is_file()
    result['apps'][role] = {'permissions': sorted(permissions), 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest()}
for folder in ('common', 'sender', 'receiver'):
    for file in (root / folder / 'src/main/java').rglob('*.java'):
        content = file.read_text()
        for token in forbidden:
            assert token not in content, (file, token)
result['forbidden_app_api_hits'] = 0
out = root / 'verification'
out.mkdir(exist_ok=True)
(out / 'static-verification.json').write_text(json.dumps(result, indent=2)+'\n')
print(json.dumps(result, indent=2))

"""Static policy regression including deliberately defective in-memory XML fixtures.
Not a substitute for a real cloud restore, Galaxy Smart Switch, or runtime permission test.
"""
from pathlib import Path
import copy
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / 'app/src/main'
A = '{http://schemas.android.com/apk/res/android}'
DOMAINS = {'root','file','database','sharedpref','external','device_root','device_file','device_database','device_sharedpref'}

def validate(manifest, rules):
    app = manifest.find('application')
    assert app is not None
    assert app.get(A+'allowBackup') == 'false'
    assert app.get(A+'dataExtractionRules') == '@xml/data_extraction_rules'
    assert rules is not None and rules.tag == 'data-extraction-rules'
    for name in ('cloud-backup','device-transfer'):
        sections = rules.findall(name)
        assert len(sections) == 1
        section = sections[0]
        assert not section.findall('include')
        assert {(e.get('domain'), e.get('path')) for e in section.findall('exclude')} == {(d,'.') for d in DOMAINS}
    perms = {p.get(A+'name') for p in manifest.findall('uses-permission')}
    assert 'android.permission.INTERNET' not in perms
    assert {'android.permission.ACCESS_FINE_LOCATION','android.permission.ACCESS_COARSE_LOCATION'} <= perms
    assert app.find('service').get(A+'exported') == 'false'
    assert not app.findall('provider')
    export = [a for a in app.findall('activity') if a.get(A+'name','').endswith('LogExportActivity')]
    assert len(export) == 1 and export[0].get(A+'exported') == 'false'
    assert not any('STORAGE' in p or 'MANAGE_EXTERNAL' in p for p in perms)

if __name__ == '__main__':
    manifest = ET.parse(ROOT / 'AndroidManifest.xml').getroot()
    rules = ET.parse(ROOT / 'res/xml/data_extraction_rules.xml').getroot()
    validate(manifest, rules)
    fixtures=[]
    m=copy.deepcopy(manifest); del m.find('application').attrib[A+'dataExtractionRules']; fixtures.append(('missing_manifest_reference',m,rules))
    fixtures.append(('missing_resource',manifest,None))
    r=copy.deepcopy(rules); r.remove(r.find('device-transfer')); fixtures.append(('missing_device_transfer',manifest,r))
    r=copy.deepcopy(rules); r.find('device-transfer').remove(r.find('device-transfer/exclude')); fixtures.append(('missing_device_domain',manifest,r))
    r=copy.deepcopy(rules); ET.SubElement(r.find('cloud-backup'),'include',domain='external',path='gnss_logs'); fixtures.append(('reallowed_sensitive_path',manifest,r))
    r=copy.deepcopy(rules); r.find('cloud-backup/exclude').set('path','gnss_logs'); fixtures.append(('partial_root_exclusion',manifest,r))
    for name,m,r in fixtures:
        try: validate(m,r)
        except AssertionError: print('PASS reject',name)
        else: raise AssertionError('Unsafe fixture accepted: '+name)
    print('PASS static policy; 6 defective fixtures rejected. Cloud/D2D runtime: NOT RUN.')

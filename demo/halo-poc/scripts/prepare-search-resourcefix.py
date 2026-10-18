"""Preserve upstream entry bytes; store search CSS/JS without ZIP deflation to remove the failing read path."""
import copy
import hashlib
import json
import zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=root/'evidence/discovery-sources/PluginSearchWidget-1.7.1-original.jar'
target=root/'PluginSearchWidget-1.7.1-resourcefix.jar'
entries=[]
with zipfile.ZipFile(source) as original, zipfile.ZipFile(target,'w') as fixed:
    assert original.testzip() is None
    for entry in original.infolist():
        body=original.read(entry); output=copy.copy(entry)
        changed=entry.filename in ('static/style.css','static/search-widget.iife.js')
        if changed: output.compress_type=zipfile.ZIP_STORED
        fixed.writestr(output,body)
        entries.append({'path':entry.filename,'sha256':hashlib.sha256(body).hexdigest(),
                        'original_method':entry.compress_type,'fixed_method':output.compress_type})
with zipfile.ZipFile(source) as original, zipfile.ZipFile(target) as fixed:
    assert fixed.testzip() is None
    assert original.namelist()==fixed.namelist()
    assert all(original.read(name)==fixed.read(name) for name in original.namelist())
report={'original_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
    'fixed_sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'upstream_version':'1.7.1',
    'all_entry_bytes_identical':True,'entries':entries,
    'scope':'Only ZIP storage method for two static resources; no upstream Java/JS/CSS/manifest changes.',
    'root_cause_limit':'Historical InflaterInputStream failure confirmed; precise intermittent trigger not reproduced after cold re-enable. This mitigation removes decompression on those routes.'}
(root/'evidence/search-resourcefix-manifest.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print('Search resource artifact prepared; all',len(entries),'entry contents identical')

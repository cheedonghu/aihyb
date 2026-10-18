"""Freeze and compare the final native adapter state around its own PoC backup/restart."""
import json
import sys
import importlib.util
from datetime import datetime,timezone
from poc_client import ROOT
spec=importlib.util.spec_from_file_location('poc_theme_verifier',ROOT/'verify-themes.py')
module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
state=module.state
current=state()
for kind in ('review','tutorial','leaderboard','pricing'):
    id='poc-'+kind; entry=current['entries'][id]['detail']; draft=json.loads(entry['draft_json']); public=current['public'][id]
    assert draft['content']['title']=='THEME_DRAFT_'+kind
    assert entry['entry']['draft_revision']!=entry['entry']['published_revision']
    assert entry['entry']['reviewed_revision'] is None
    assert 'THEME_DRAFT_' not in public['content']['title']
assert json.loads(current['entries']['poc-pricing']['detail']['draft_json'])['data']['plans'][0]['amount']=='59.91'
assert current['public']['poc-pricing']['data']['plans'][0]['amount']=='49.00'
file=ROOT/'evidence/native-final-before-restart.json'
if sys.argv[1]=='before':
    render=current['entries']['poc-theme-render']
    a=render['post_annotations']
    assert a['aihyb.run/publisher']=='aihyb-admin', 'Final real browser publish did not complete'
    assert a['aihyb.run/draft-revision']==a['aihyb.run/approved-revision']==a['aihyb.run/published-revision']
    (ROOT/'evidence/native-browser-final.json').write_text(json.dumps({'timestamp_utc':datetime.now(timezone.utc).isoformat(),
        'native_publish_with_window_snapshot':True,'noop_save_kept_approval':True,
        'unapproved_pricing_draft_remained_private':True,'published_render_revision':a['aihyb.run/published-revision'],'complete':True},indent=2))
    file.write_text(json.dumps(current,ensure_ascii=False,indent=2))
    print('Frozen final native state before backup/restart')
else:
    before=json.loads(file.read_text()); assert current==before,'Backup/restart changed content state'
    report={'timestamp_utc':datetime.now(timezone.utc).isoformat(),'four_modules_draft_B_public_A':True,
        'pricing_draft':'59.91','pricing_public':'49.00','all_business_state_unchanged':True,
        'counts':{key:len(value) for key,value in current.items()},'complete':True}
    (ROOT/'evidence/native-final-persistence.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print('Final native backup/restart persistence and all four pending drafts PASS')

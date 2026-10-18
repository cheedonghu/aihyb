"""Admin-only native migration: repair known dangling slug references, prove all content state is unchanged."""
import importlib.util
import json
from poc_client import Client,ROOT
spec=importlib.util.spec_from_file_location('poc_themes',ROOT/'verify-themes.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
before=module.state(); c=Client()
result=c.request('/apis/console.api.aihyb.run/v1alpha1/native-migrate','POST')
after=module.state(); changes=[]
for id,entry in before['entries'].items():
    previous=entry['post_spec']['categories'];new=after['entries'][id]['post_spec']['categories']
    changes.append({'content_id':id,'before':previous,'after':new})
    assert new==['poc-'+{'review':'reviews','tutorial':'tutorials','leaderboard':'leaderboards','pricing':'prices'}[entry['detail']['entry']['kind']]]
    # Compare every original field other than the intentional fixed module-category identity.
    after['entries'][id]['post_spec']['categories']=previous
assert before==after,'Category migration changed editorial/approval/publication/revision state'
(ROOT/'evidence/native-category-repair.json').write_text(json.dumps({'admin_result':result,'changes':changes,'all_other_business_state_unchanged':True,'complete':True},ensure_ascii=False,indent=2))
print('Native category identities repaired; all drafts, approvals, published content and snapshots unchanged')

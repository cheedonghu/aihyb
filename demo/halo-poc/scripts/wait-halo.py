"""Readiness only; no service mutations or credential output."""
import json
import time
import urllib.error
import urllib.request
from poc_client import BASE
started=time.monotonic()
for attempt in range(45):
    try:
        state=json.load(urllib.request.urlopen(BASE+'/actuator/health/readiness',timeout=3))
        if state.get('status')=='UP':print('PoC Halo ready after',round(time.monotonic()-started,1),'seconds');break
    except (OSError,urllib.error.URLError):pass
    time.sleep(2)
else:raise RuntimeError('PoC Halo readiness timed out')

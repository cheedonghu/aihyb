"""Print only public HTML occurrences of an obsolete origin to locate template/config issues."""
import re,urllib.request
for path in ('/aihyb','/archives/aihyb-poc-review','/rss.xml','/sitemap.xml'):
    raw=urllib.request.urlopen('https://aihyb.com'+path,timeout=30).read().decode()
    print(path)
    for occurrence in re.finditer(r'127\.0\.0\.1:18090|http://aihyb\.com',raw):
        print(raw[max(0,occurrence.start()-110):occurrence.end()+140].replace('\n',' ')[:280])

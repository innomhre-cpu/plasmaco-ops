import json, re, collections, datetime
d = json.load(open('wb_43.json'))
def num(v):
    return float(v) if isinstance(v,(int,float)) and not isinstance(v,bool) else None
print("### Block dates per sheet (raw formula/value -> cached)")
for sh in d['sheets']:
    if sh['state']=='hidden': continue
    ds=[]
    for b in sh['blocks']:
        dc=b['date_cell']; ds.append(dc[1] if dc else None)
    print(sh['sheet'], len(ds), [str(x)[:10] for x in ds])
print("\n### opening sumif / formula locations")
for sh in d['sheets']:
    if sh['state']=='hidden': continue
    c=collections.Counter()
    for bi,b in enumerate(sh['blocks']):
        for r in b['rows']:
            f=r['open_f']
            if isinstance(f,str) and ('SUMIF' in f or '!' in f): c[(bi, f.split('!')[0][:25] if '!' in f else 'SUMIF')]+=1
    if c: print(sh['sheet'], dict(c))
print("\n### opening != prev closing grouped")
g=collections.Counter()
for sh in d['sheets']:
    if sh['state']=='hidden': continue
    prev=None
    for bi,b in enumerate(sh['blocks']):
        if prev:
            pm={r['name']:r for r in prev['rows']}
            for r in b['rows']:
                p=pm.get(r['name'])
                if p and num(r['open']) is not None and num(p['closing']) is not None and abs(num(r['open'])-num(p['closing']))>1e-6:
                    g[(sh['sheet'], bi, str(b['date_cell'][1])[:10])]+=1
        prev=b
for k,v in sorted(g.items()): print("  ",k,v)
print("\n### month boundary: first block opening vs previous sheet last block closing")
vis=[s for s in d['sheets'] if s['state']!='hidden']
for a,b in zip(vis,vis[1:]):
    la=a['blocks'][-1]; fb=b['blocks'][0]
    pm={r['name']:r for r in la['rows']}
    mism=0; tot=0; ex=[]
    for r in fb['rows']:
        p=pm.get(r['name'])
        if not p: continue
        tot+=1
        o,c=num(r['open']),num(p['closing'])
        if o is not None and c is not None and abs(o-c)>1e-6: mism+=1; ex.append((r['name'][-12:],c,o))
    print(f"  {a['sheet']}(last {str(la['date_cell'][1])[:10]}) -> {b['sheet']}(first {str(fb['date_cell'][1])[:10]}): {mism}/{tot} mismatched", ex[:4])
print("\n### closing_ne_formula details")
for sh in d['sheets']:
    if sh['state']=='hidden': continue
    for b in sh['blocks']:
        for r in b['rows']:
            o,i,ou,c=(num(r[k]) for k in ('open','inward','outward','closing'))
            if None not in (o,i,ou,c) and abs(o+i-ou-c)>1e-6: print("  ",sh['sheet'],r['row'],r['name'][-14:],r['open_f'],r['inward_f'],r['outward_f'],r['closing_f'],(o,i,ou,c)); break
print("\n### TOTAL formulas per sheet (distinct patterns + misses)")
for sh in d['sheets']:
    if sh['state']=='hidden': continue
    pats=collections.Counter(); miss=0; nototal=0
    for b in sh['blocks']:
        t=b['total']
        if not t: nototal+=1; continue
        f=str(t['formula'])
        m=re.search(r'([A-Z]+)(\d+):([A-Z]+)(\d+)', f)
        rows=[r['row'] for r in b['rows']]
        if m:
            lo,hi=int(m.group(2)),int(m.group(4)); off=(lo-rows[0], hi-rows[-1]); pats[(re.sub(r'\d+','n',f), off)]+=1
        else: pats[f]+=1
    print("  ",sh['sheet'], dict(pats), 'no_total=',nototal)

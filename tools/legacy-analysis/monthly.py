import json, re, collections
d = json.load(open('wb_43.json')); iso = json.load(open('iso.json'))
rx = re.compile(r'DN\s*(\d+)\s*PN\s*([\d.]+)', re.I)
def key(n):
    m = rx.search(n)
    if not m: return None
    pn = m.group(2); pn = pn.rstrip('0').rstrip('.') if '.' in pn else pn
    return f'{int(m.group(1))}|{pn}'
def num(v): return float(v) if isinstance(v,(int,float)) and not isinstance(v,bool) else 0.0
rows=[]
print(f"{'sheet':5} {'blocks':>6} {'inward_m':>10} {'outward_m':>10} {'kg_sheetH':>11} {'kg_ISO':>11} {'diff%':>6} {'TOTALcells':>11} {'RM_issue':>10} {'RM_recv':>9} factor_mismatch_blocks")
for sh in d['sheets']:
    if sh['state']=='hidden': continue
    inw=outw=kgs=kgi=tot=0; mm_blocks=[]
    for bi,b in enumerate(sh['blocks']):
        mm=0
        for r in b['rows']:
            k=key(r['name']); ref=iso.get(k,{}).get('kg_per_m') if k else None
            f=r['kg_per_m']
            if ref and isinstance(f,(int,float)) and abs(f-ref)>0.005: mm+=1
            i=num(r['inward']); inw+=i; outw+=num(r['outward'])
            kgs+= i*num(f); kgi += i*(ref if ref else num(f))
        mm_blocks.append(mm)
        if b['total'] and isinstance(b['total']['value'],(int,float)): tot+=b['total']['value']
    rmi=sum(num(r['issue']) for r in sh['rm']['rows']); rmr=sum(num(r['recv']) for r in sh['rm']['rows'])
    print(f"{sh['sheet']:5} {len(sh['blocks']):6} {inw:10.0f} {outw:10.0f} {kgs:11.0f} {kgi:11.0f} {100*(kgs-kgi)/kgi if kgi else 0:6.1f} {tot:11.0f} {rmi:10.0f} {rmr:9.0f} {mm_blocks}")

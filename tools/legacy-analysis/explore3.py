import json, re, collections
d = json.load(open('wb_43.json'))
def num(v): return float(v) if isinstance(v,(int,float)) and not isinstance(v,bool) else None
sumif_nonzero=0; sumif_total=0; ta_in=[]; ta_out=[]; lit_close=[]; dnpn=[]
for sh in d['sheets']:
    if sh['state']=='hidden': continue
    for b in sh['blocks']:
        for r in b['rows']:
            for k in ('inward','outward'):
                f=r[k+'_f']
                if isinstance(f,str) and 'SUMIF' in f:
                    sumif_total+=1
                    if num(r[k]): sumif_nonzero+=1
                if isinstance(f,str) and re.fullmatch(r'=[\d.\s+*\-/()]+', f):
                    (ta_in if k=='inward' else ta_out).append((sh['sheet'], f))
            if isinstance(r['closing_f'],(int,float)): lit_close.append((sh['sheet'], r['row'], r['name'][-14:], r['open'], r['inward'], r['outward'], r['closing_f']))
            if 'DN PN' in r['name']: dnpn.append((sh['sheet'], r['row'], r['inward'], r['outward'], r['closing']))
print("SUMIF cells:", sumif_total, "non-zero:", sumif_nonzero)
print("typed arithmetic inward:", len(ta_in), ta_in[:5])
print("typed arithmetic outward:", len(ta_out), ta_out[:6])
mx = max(ta_out, key=lambda x: x[1].count('+'))
print("max terms in one outward cell:", mx[1].count('+')+1, mx)
print("literal closing cells:", len(lit_close), lit_close[:6])
print("'DN PN6.3' rows:", len(dnpn), dnpn[:3])
# latest stock snapshot
last = [s for s in d['sheets'] if s['sheet']=='oct'][0]['blocks'][-1]
print("latest block date:", last['date_cell'])
cl = [(r['name'], num(r['closing'])) for r in last['rows']]
print("products:", len(cl), "with stock>0:", sum(1 for _,c in cl if c and c>0), "total metres:", round(sum(c for _,c in cl if c),1))
print("zero/neg stock:", sum(1 for _,c in cl if c is not None and c<=0))
# Home drawing hyperlinks

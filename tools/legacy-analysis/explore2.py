import json, re, collections
d = json.load(open('wb_43.json'))
S={s['sheet']:s for s in d['sheets']}
def num(v): return float(v) if isinstance(v,(int,float)) and not isinstance(v,bool) else None
print("feb block0 sample open_f:", [r['open_f'] for r in S['feb']['blocks'][0]['rows'][:3]], [r['open'] for r in S['feb']['blocks'][0]['rows'][:3]])
print("feb block2 sample:", [(r['name'][-12:], r['open_f'], r['open']) for r in S['feb']['blocks'][2]['rows'][:4]])
print("july total literal blocks:", [(b['total']['label_cell'], b['total']['formula'], b['total']['value']) for b in S['july']['blocks'] if b['total'] and b['total']['formula'] in (0,'0')][:3])
# kg column in july blocks
b=S['july']['blocks'][0]; print("july b0 kg_f:", collections.Counter(type(r['kg_f']).__name__+':'+str(r['kg_f'])[:12] for r in b['rows']).most_common(4))
print("\n### RM sections")
for sn in ['feb','mar','apr','may','jun','july','aug','sep','oct']:
    rm=S[sn]['rm']; print(f"== {sn} header {rm['header_cell']} cols {rm['cols']} rows {len(rm['rows'])}")
    for r in rm['rows'][:40]:
        if all(r[k] in (None,'') for k in ('open_f','recv_f','issue_f')) and r['bal'] in (None,0): continue
        print("   ", r['row'], str(r['date_f'])[:10], '|O', r['open_f'], '|R', r['recv_f'], '|I', r['issue_f'], '|B', r['bal_f'], '=>', r['open'], r['recv'], r['issue'], r['bal'])

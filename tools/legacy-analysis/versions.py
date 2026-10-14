import json, collections
order = ['46','6-1','45','42','44','43']
D = {v: json.load(open(f'wb_{v}.json')) for v in order}
def idx(d):
    out={}
    for sh in d['sheets']:
        if sh['state']=='hidden': continue
        for bi,b in enumerate(sh['blocks']):
            for r in b['rows']:
                for k in ('open','inward','outward','closing','kg_per_m','min_qty'):
                    out[(sh['sheet'],bi,r['name'],k)] = (r[k], r[k+'_f'])
        for r in sh['rm']['rows']:
            for k in ('open','recv','issue','bal'):
                out[(sh['sheet'],'RM',r['row'],k)] = (r[k], r[k+'_f'])
    return out
I = {v: idx(D[v]) for v in order}
for a,b in zip(order, order[1:]):
    A,B = I[a],I[b]
    changed = [(k, A[k], B[k]) for k in A if k in B and A[k][0]!=B[k][0] and not (isinstance(A[k][0],(int,float)) and isinstance(B[k][0],(int,float)) and abs(A[k][0]-B[k][0])<1e-6)]
    added = [k for k in B if k not in A]
    bysheet = collections.Counter((k[0], k[1] if k[1]=='RM' else 'FG') for k,_,_ in changed)
    print(f"\n{a} -> {b}: changed {len(changed)} existing cells, added {len(added)}; by sheet: {dict(bysheet)}")
    for k,x,y in [c for c in changed if c[0][0] not in ('oct',)][:8]: print("    ", k, x, '->', y)

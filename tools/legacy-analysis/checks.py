import json, re, sys, collections, datetime
d = json.load(open(sys.argv[1]))
iso = json.load(open('iso.json')) if len(sys.argv)>2 else None
def num(v):
    if isinstance(v,(int,float)) and not isinstance(v,bool): return float(v)
    return None
def classify(f):
    if f is None: return 'blank'
    if isinstance(f,(int,float)): return 'literal'
    if isinstance(f,str) and not f.startswith('='):
        try: float(f); return 'literal'
        except: return 'text'
    if '[' in f: return 'external_link'
    if 'SUMIF' in f: return 'sumif'
    if '!' in f: return 'cross_sheet'
    if re.fullmatch(r'=[\d.\s+*\-/()]+', f): return 'typed_arithmetic'
    if re.fullmatch(r'=\$?[A-Z]{1,2}\$?\d+', f): return 'cell_ref'
    return 'formula'
names = collections.Counter(); per_sheet_names = {}
stats = collections.defaultdict(collections.Counter)
issues = collections.defaultdict(list)
for sh in d['sheets']:
    if sh['state']=='hidden': continue
    sn = sh['sheet']; prev = None
    for bi,b in enumerate(sh['blocks']):
        dc = b['date_cell']
        stats['date_types'][type(dc[1]).__name__ if dc else 'none'] += 1
        for r in b['rows']:
            names[r['name']] += 1
            for k in ('open','inward','outward','closing','kg','kg_per_m','min_qty'):
                stats[k+'_class'][classify(r[k+'_f'])] += 1
            o,i,ou,c = (num(r[k]) for k in ('open','inward','outward','closing'))
            if None not in (o,i,ou,c) and abs(o+i-ou-c)>1e-6: issues['closing_ne_formula'].append((sn,r['row'],r['name']))
            if c is not None and c < -1e-9: issues['negative_closing'].append((sn, dc and dc[2], r['name'], round(c,2)))
            if i is not None and i < 0: issues['negative_inward'].append((sn,r['row'],r['name'],i))
            if ou is not None and ou < 0: issues['negative_outward'].append((sn,r['row'],r['name'],ou))
            if isinstance(r['inward_f'],str) and r['inward_f'].startswith('=') and classify(r['inward_f'])=='cross_sheet':
                if num(r['inward']) and num(r['inward'])>0: issues['inward_from_other_sheet_nonzero'].append((sn,r['row'],r['name'],r['inward_f'],r['inward']))
            if isinstance(r['outward_f'],str) and classify(r['outward_f'])=='cross_sheet':
                if num(r['outward']) and num(r['outward'])>0: issues['outward_from_other_sheet_nonzero'].append((sn,r['row'],r['name'],r['outward_f'],r['outward']))
            kf = classify(r['kg_f'])
            if kf in ('blank','literal') and num(r['inward']):
                issues['kg_not_formula_with_inward'].append((sn,r['row'],r['name'],r['kg_f'],r['inward']))
        # chain check vs previous block
        if prev is not None:
            pm = {r['name']: r for r in prev['rows']}
            for r in b['rows']:
                p = pm.get(r['name'])
                if not p: issues['product_not_in_prev_block'].append((sn,r['row'],r['name'])); continue
                o, pc = num(r['open']), num(p['closing'])
                cls = classify(r['open_f'])
                stats['open_link'][cls] += 1
                if o is not None and pc is not None and abs(o-pc)>1e-6:
                    issues['opening_ne_prev_closing'].append((sn, b['date_cell'] and b['date_cell'][2], r['name'], pc, o, r['open_f']))
        # total coverage
        t = b['total']
        if t and isinstance(t['formula'],str):
            m = re.search(r'([A-Z]+)(\d+):([A-Z]+)(\d+)', t['formula'])
            if m:
                lo,hi = int(m.group(2)), int(m.group(4))
                rows = [r['row'] for r in b['rows']]
                missed = [r for r in rows if r<lo or r>hi]
                if missed: issues['total_range_misses_rows'].append((sn, t['label_cell'], t['formula'], len(missed), [x['name'] for x in b['rows'] if x['row'] in missed][:3]))
        elif not t: issues['block_without_total'].append((sn,b['header_row']))
        prev = b
print("== distinct product names (visible sheets):", len(names))
for k,v in stats.items(): print(k, dict(v))
for k,v in issues.items():
    print(f"\n## {k}: {len(v)}")
    for x in v[:12]: print("   ", x)
json.dump({'names': names}, open('names.json','w'))

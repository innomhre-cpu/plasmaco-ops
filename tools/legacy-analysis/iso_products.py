import json, re, collections, openpyxl
wb = openpyxl.load_workbook('../src_xl/wb_43.xlsm', data_only=True)
ws = wb['Rs iso 4427-2']
pn_cols = {'4':'I','5':'L','6.3':'O','8':'R','10':'U','12.5':'X','16':'AA','20':'AD','25':'AG'}
sdr = {'4':41,'5':33,'6.3':26,'8':21,'10':17,'12.5':13.6,'16':11,'20':9,'25':7.4}
thk_cols = {'4':('G','H'),'5':('J','K'),'6.3':('M','N'),'8':('P','Q'),'10':('S','T'),'12.5':('V','W'),'16':('Y','Z'),'20':('AB','AC'),'25':('AE','AF')}
iso = {}
for r in range(10, 30):
    dn = ws[f'C{r}'].value
    for pn,col in pn_cols.items():
        w = ws[f'{col}{r}'].value
        tmin, tmax = (ws[f'{c}{r}'].value for c in thk_cols[pn])
        iso[f'{dn}|{pn}'] = {'dn': dn, 'pn': pn, 'sdr': sdr[pn], 'kg_per_m': w if isinstance(w,(int,float)) else None,
                             'wall_min': tmin if isinstance(tmin,(int,float)) else None, 'wall_max': tmax if isinstance(tmax,(int,float)) else None,
                             'od_min': ws[f'D{r}'].value, 'od_max': ws[f'E{r}'].value, 'ovality_max': ws[f'F{r}'].value}
json.dump(iso, open('iso.json','w'), indent=0)
print("ISO entries with weight:", sum(1 for v in iso.values() if v['kg_per_m']))
d = json.load(open('wb_43.json'))
rx = re.compile(r'DN\s*(\d+)\s*PN\s*([\d.]+)', re.I)
latest = {}   # name -> (sheet, factor)
allnames = collections.OrderedDict()
factors = collections.defaultdict(set); minq = collections.defaultdict(set)
for sh in d['sheets']:
    if sh['state']=='hidden': continue
    for b in sh['blocks']:
        for r in b['rows']:
            allnames.setdefault(r['name'], sh['sheet'])
            if isinstance(r['kg_per_m'],(int,float)): factors[r['name']].add(round(r['kg_per_m'],4))
            if isinstance(r['min_qty'],(int,float)): minq[r['name']].add(r['min_qty'])
            latest[r['name']] = (sh['sheet'], r['kg_per_m'])
norm = collections.defaultdict(list)
mism = []; noiso=[]
for n in allnames:
    m = rx.search(n)
    if not m: print("UNPARSED:", n); continue
    dn, pn = int(m.group(1)), m.group(2).rstrip('0').rstrip('.') if '.' in m.group(2) else m.group(2)
    key = f'{dn}|{pn}'; norm[key].append(n)
    ref = iso.get(key, {}).get('kg_per_m')
    sheet_f = latest[n][1]
    if ref is None: noiso.append((n, sheet_f))
    elif not isinstance(sheet_f,(int,float)) or abs(sheet_f-ref) > 0.005:
        mism.append((n, sheet_f, ref))
print("distinct names:", len(allnames), " distinct DN|PN:", len(norm))
print("\nNAME VARIANTS for same DN|PN:")
for k,v in norm.items():
    if len(v)>1: print("  ", k, v)
print("\nfactor changes over the year (name: set):")
for n,s in factors.items():
    if len(s)>1: print("  ", n, sorted(s))
print(f"\nkg/m factor vs ISO 4427-2 sheet: {len(mism)} mismatches of {len(allnames)-len(noiso)} comparable")
for x in mism: print("   ", x)
print("\nNo ISO weight available:", noiso)
print("\nmin qty variants:", {n:sorted(s) for n,s in minq.items() if len(s)>1})
print("min qty distinct values:", collections.Counter(v for s in minq.values() for v in s))
json.dump({'norm': norm, 'mism': mism, 'noiso': noiso}, open('products.json','w'))

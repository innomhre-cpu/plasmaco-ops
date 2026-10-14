import struct, sys
data=open(sys.argv[1],'rb').read()
hdr=data[:512]
ssz=1<<struct.unpack_from('<H',hdr,30)[0]; mssz=1<<struct.unpack_from('<H',hdr,32)[0]
nfat=struct.unpack_from('<I',hdr,44)[0]; dirstart=struct.unpack_from('<I',hdr,48)[0]
mcut=struct.unpack_from('<I',hdr,56)[0]; minifatstart=struct.unpack_from('<I',hdr,60)[0]
difat=list(struct.unpack_from('<109I',hdr,76))
def sec(i): return data[512+i*ssz:512+(i+1)*ssz]
fat=[]
for s in difat[:nfat]: fat+=struct.unpack(f'<{ssz//4}I',sec(s))
def chain(st):
    out=[];
    while st<0xFFFFFFFA: out.append(st); st=fat[st]
    return out
def read(st): return b''.join(sec(s) for s in chain(st))
d=read(dirstart); ents=[]
for i in range(len(d)//128):
    e=d[i*128:(i+1)*128]; nl=struct.unpack_from('<H',e,64)[0]
    name=e[:max(nl-2,0)].decode('utf-16le'); typ=e[66]
    st=struct.unpack_from('<I',e,116)[0]; size=struct.unpack_from('<Q',e,120)[0]
    ents.append((name,typ,st,size))
root=ents[0]; ministream=read(root[2]); minifat=[]
mf=read(minifatstart) if minifatstart<0xFFFFFFFA else b''
minifat=list(struct.unpack(f'<{len(mf)//4}I',mf))
def readstream(st,size):
    if size<mcut:
        out=b''
        while st<0xFFFFFFFA: out+=ministream[st*mssz:(st+1)*mssz]; st=minifat[st]
        return out[:size]
    return read(st)[:size]
def decompress(c):
    assert c[0]==1; pos=1; out=bytearray()
    while pos<len(c):
        h=struct.unpack_from('<H',c,pos)[0]; size=(h&0xFFF)+3; flag=h>>15; chunk=c[pos+2:pos+size]; pos+=size
        if not flag: out+=chunk[:4096]; continue
        dec=bytearray(); i=0
        while i<len(chunk):
            fb=chunk[i]; i+=1
            for b in range(8):
                if i>=len(chunk): break
                if not (fb>>b)&1: dec.append(chunk[i]); i+=1
                else:
                    tok=struct.unpack_from('<H',chunk,i)[0]; i+=2
                    dp=len(dec); bc=max((dp-1).bit_length(),4); lm=0xFFFF>>bc
                    ln=(tok&lm)+3; off=(tok>>(16-bc))+1
                    for _ in range(ln): dec.append(dec[-off])
        out+=dec
    return bytes(out)
streams={e[0]:e for e in ents}
dirc=decompress(readstream(streams['dir'][2],streams['dir'][3]))
# parse module names + offsets
i=0; mods=[]; cur={}
while i<len(dirc)-6:
    rid,sz=struct.unpack_from('<HI',dirc,i)
    if rid==9: sz=6
    body=dirc[i+6:i+6+sz]
    if rid==0x19: cur={'name':body.decode('latin1')}
    if rid==0x1A: cur['stream']=body.decode('latin1')
    if rid==0x31: cur['off']=struct.unpack('<I',body)[0]; mods.append(cur)
    i+=6+sz
for m in mods:
    e=streams.get(m['stream'])
    if not e: continue
    raw=readstream(e[2],e[3])
    try: src=decompress(raw[m['off']:]).decode('latin1')
    except Exception as ex: src=f'<err {ex}>'
    lines=[l for l in src.splitlines() if not l.startswith('Attribute ')]
    print(f"=== {m['name']} ({len(lines)} lines)"); print("\n".join(lines[:200]))

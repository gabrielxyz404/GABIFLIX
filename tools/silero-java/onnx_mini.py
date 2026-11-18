"""Leitor mínimo de ONNX (protobuf wire) sem dependências além de numpy."""
import struct, numpy as np
def varint(b,i):
    r=0;s=0
    while True:
        c=b[i];i+=1;r|=(c&0x7f)<<s;s+=7
        if not c&0x80:return r,i
def fields(b):
    i=0;n=len(b)
    while i<n:
        k,i=varint(b,i);f,w=k>>3,k&7
        if w==0:v,i=varint(b,i)
        elif w==1:v=b[i:i+8];i+=8
        elif w==5:v=b[i:i+4];i+=4
        elif w==2:
            l,i=varint(b,i);v=b[i:i+l];i+=l
        else:raise ValueError(w)
        yield f,w,v
DT={1:np.float32,7:np.int64,6:np.int32,11:np.float64,9:np.bool_,3:np.int8,2:np.uint8,10:np.float16}
def tensor(b):
    dims=[];dt=1;name="";raw=None;fl=[];i64=[]
    for f,w,v in fields(b):
        if f==1:
            if w==2:
                j=0
                while j<len(v):x,j=varint(v,j);dims.append(x)
            else:dims.append(v)
        elif f==2:dt=v
        elif f==8:name=v.decode()
        elif f==9:raw=v
        elif f==4:fl.append(v)
        elif f==7:
            if w==2:
                j=0
                while j<len(v):
                    x,j=varint(v,j);i64.append(x-(1<<64) if x>>63 else x)
            else:i64.append(v-(1<<64) if v>>63 else v)
    if raw is not None:a=np.frombuffer(raw,dtype=DT[dt])
    elif fl:a=np.frombuffer(b"".join(fl),dtype=np.float32)
    else:a=np.array(i64,dtype=np.int64)
    return name,a.reshape(dims) if dims else a.reshape(())
def attr(b):
    d={"name":"","type":0}
    for f,w,v in fields(b):
        if f==1:d["name"]=v.decode()
        elif f==2:d["f"]=struct.unpack("<f",v)[0]
        elif f==3:d["i"]=v
        elif f==4:d["s"]=v
        elif f==5:d["t"]=tensor(v)[1]
        elif f==6:d["g"]=graph(v)
        elif f==7:d.setdefault("floats",[]).append(struct.unpack("<f",v)[0])
        elif f==8:
            if w==2:
                j=0;l=d.setdefault("ints",[])
                while j<len(v):x,j=varint(v,j);l.append(x)
            else:d.setdefault("ints",[]).append(v)
        elif f==20:d["type"]=v
    return d
def node(b):
    n={"in":[],"out":[],"op":"","name":"","attr":{}}
    for f,w,v in fields(b):
        if f==1:n["in"].append(v.decode())
        elif f==2:n["out"].append(v.decode())
        elif f==3:n["name"]=v.decode()
        elif f==4:n["op"]=v.decode()
        elif f==5:
            a=attr(v);n["attr"][a["name"]]=a
    return n
def vinfo(b):
    name="";shape=[]
    for f,w,v in fields(b):
        if f==1:name=v.decode()
        elif f==2:
            for f2,w2,v2 in fields(v):
                if f2==1:
                    for f3,w3,v3 in fields(v2):
                        if f3==2:
                            for f4,w4,v4 in fields(v3):
                                if f4==1:
                                    for f5,w5,v5 in fields(v4):
                                        if f5==1:shape.append(v5)
                                        elif f5==2:shape.append(v5.decode())
    return name,shape
def graph(b):
    g={"nodes":[],"init":{},"in":[],"out":[]}
    for f,w,v in fields(b):
        if f==1:g["nodes"].append(node(v))
        elif f==5:
            n,a=tensor(v);g["init"][n]=a
        elif f==11:g["in"].append(vinfo(v))
        elif f==12:g["out"].append(vinfo(v))
    return g
def load(path):
    b=open(path,"rb").read()
    for f,w,v in fields(b):
        if f==7:return graph(v)

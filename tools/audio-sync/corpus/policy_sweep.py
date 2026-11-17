#!/usr/bin/env python3
"""Varredura de politicas de aceitacao (z minimo, banda morta, rejeitar escalas 0,999/1,001) sobre os CSVs do eval final. Uso: policy_sweep.py ROOT_res DIR_corpus"""
import sys,re
sys.path.insert(0,sys.argv[2]); import decision
rows=decision.load(sys.argv[1],"eval-o1.csv"); rows2=decision.load(sys.argv[1],"eval-o2.csv")
# need off,scale columns
import csv,glob,os
def addcols(rows,name):
    it=[]
    for p in sorted(glob.glob(os.path.join(sys.argv[1],"**",name),recursive=True)):
        for r in csv.reader(open(p,encoding="utf-8")):
            if len(r)>=24: it.append((float(r[10]) if r[10] not in("","-") else 0.0,float(r[11]) if r[11] not in("","-") else 1.0,float(r[13]) if r[13] not in ("","-") else 0))
    for r,(o,s,m) in zip(rows,it): r["off"]=o; r["scale"]=s; r["margin"]=m
addcols(rows,"eval-o1.csv"); addcols(rows2,"eval-o2.csv")
def pol(r,Z,D,noknown):
    if not r["acc"] or r["z"]<Z: return False
    sc=r["scale"]
    if abs(sc-1)<1e-4 and abs(r["off"])<D: return False
    if noknown and 0<abs(sc-1)<0.0015: return False
    return True
for vad,cfg in [("dsp","3x90d"),("silero","3x90d"),("dsp","4x90d"),("silero","4x90d")]:
  print("==",vad,cfg)
  for Z in (9,11,13):
    for D in (600,1100,2000,6000):
      for nk in (0,1):
        rs=[r for r in rows if r["vad"]==vad and r["cfg"]==cfg]
        d=[r for r in rs if r["cat"] in("offset","scale","combo")]
        ok=sum(1 for r in d if pol(r,Z,D,nk) and 0<=r["relmed"]<=250)
        # distortions with offset > D only meaningful; wrong accepted:
        wr=sum(1 for r in d if pol(r,Z,D,nk) and not 0<=r["relmed"]<=250)
        sf=[r for r in rs if r["cat"]=="safe"]; mv=sum(1 for r in sf if pol(r,Z,D,nk) and r["med"]>150)
        nat=[r for r in rs if r["cat"]=="natural" and r["before"] is not None]
        w=sum(1 for r in nat if pol(r,Z,D,nk) and r["med"]>r["before"]+150); b=sum(1 for r in nat if pol(r,Z,D,nk) and r["med"]<r["before"]-150)
        ng=sum(1 for r in rs if r["cat"]=="neg" and pol(r,Z,D,nk))
        sf2=[r for r in rows2 if r["vad"]==vad and r["cfg"]==cfg and r["cat"]=="safe"]; mv2=sum(1 for r in sf2 if pol(r,Z,D,nk) and r["med"]>150)
        nat2=[r for r in rows2 if r["vad"]==vad and r["cfg"]==cfg and r["cat"]=="natural" and r["before"] is not None]
        w2=sum(1 for r in nat2 if pol(r,Z,D,nk) and r["med"]>r["before"]+150)
        print("Z>=%d D=%d noknown=%d: correct %d/%d wrongacc %d | safe moved v1 %d v2 %d /93 | nat worse v1 %d v2 %d better %d /93 | neg %d"%(Z,D,nk,ok,len(d),wr,mv,mv2,w,w2,b,ng))
print("== wrong-accepted breakdown silero 3x90d Z9 D1100 nk1")
import collections
c=collections.Counter(); t=collections.Counter()
for r in rows:
    if r["vad"]=="silero" and r["cfg"]=="3x90d" and r["cat"] in("offset","scale","combo"):
        t[(r["cat"],r["param"][:6])]+=1
        if pol(r,9,1100,1) and not 0<=r["relmed"]<=250: c[(r["cat"],r["param"][:6])]+=1
for k in sorted(t): print(k,t[k],c[k])

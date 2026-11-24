"""Confere que o MKV tem as falas da faixa de legenda no indice (Cues). Uso: mkvcues.py arquivo.mkv NUMERO_DA_FAIXA"""
import sys
f = open(sys.argv[1], 'rb'); data = f.read()
TRACK = int(sys.argv[2]) if len(sys.argv) > 2 else 3


def vint(b, p, keep):
    first = b[p]; l = 1; m = 0x80
    while l <= 8 and not (first & m):
        l += 1; m >>= 1
    v = first if keep else first & (m - 1)
    for i in range(1, l):
        v = (v << 8) | b[p + i]
    return v, l, (not keep) and v == (1 << (7 * l)) - 1


def hdr(b, p):
    i, il, _ = vint(b, p, True)
    s, sl, unk = vint(b, p + il, False)
    return i, s, il + sl, unk


i, s, h, _ = hdr(data, 0)
p = h + s
sid, ssz, shl, sunk = hdr(data, p)
segStart = p + shl
print(hex(sid), ssz, sunk, 'segStart', segStart)
q = segStart
seek = {}
while q < len(data):
    i, s, h, u = hdr(data, q)
    print(hex(i), s, 'at', q - segStart)
    if i == 0x1F43B675:
        break
    if i == 0x114D9B74:
        r = q + h
        while r < q + h + s:
            ii, ss, hh, _ = hdr(data, r)
            if ii == 0x4DBB:
                t = r + hh; sid_ = None; pos = None
                while t < r + hh + ss:
                    a, b, c, _ = hdr(data, t)
                    if a == 0x53AB:
                        sid_ = int.from_bytes(data[t + c:t + c + b], 'big')
                    if a == 0x53AC:
                        pos = int.from_bytes(data[t + c:t + c + b], 'big')
                    t += c + b
                seek[sid_] = pos
            r += hh + ss
    q += h + s
print({hex(k): v for k, v in seek.items()})
cp = segStart + seek[0x1C53BB6B]
i, s, h, _ = hdr(data, cp)
print('cues', hex(i), s)
r = cp + h
sub = []
while r < cp + h + s:
    a, b, c, _ = hdr(data, r)
    if a == 0xBB:
        t = r + c; time = None; ents = []
        while t < r + c + b:
            x, y, z, _ = hdr(data, t)
            if x == 0xB3:
                time = int.from_bytes(data[t + z:t + z + y], 'big')
            if x == 0xB7:
                u = t + z; d = {}
                while u < t + z + y:
                    k, l, m, _ = hdr(data, u)
                    d[k] = int.from_bytes(data[u + m:u + m + l], 'big'); u += m + l
                ents.append(d)
            t += z + y
        for d in ents:
            if d.get(0xF7) == TRACK:
                sub.append((time, d))
    r += c + b
print(len(sub), sub[:3])
tracks_in_cues = set()
# read the first subtitle block through cluster position + relative position
for time, d in sub[:3]:
    cpos = segStart + d[0xF1]
    ci, cs, ch, _ = hdr(data, cpos)
    bpos = cpos + ch + d.get(0xF0, 0)
    bi, bs, bh, _ = hdr(data, bpos)
    print('cluster', hex(ci), 'header', ch, 'block id', hex(bi), bs, data[bpos + bh: bpos + bh + 40])

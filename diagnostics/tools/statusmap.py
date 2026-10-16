"""Print the saved generation Status of each chunk in a box (read-only).

usage: statusmap.py <dimension dir> minChunkX maxChunkX minChunkZ maxChunkZ [--summary] [--holes]

  <dimension dir>  folder containing region/ (Paper 26.x: world/dimensions/minecraft/overworld)
  --summary        print only the status counts, not the per-chunk map
  --holes          list "holes": non-full chunks whose 8 neighbours are all full (a wedged chunk mid-generation)
"""
import os, struct, sys, zlib, collections

root = sys.argv[1]
x0, x1, z0, z1 = map(int, sys.argv[2:6])
summary = "--summary" in sys.argv


def read(cx, cz):
    p = os.path.join(root, "region", f"r.{cx >> 5}.{cz >> 5}.mca")
    if not os.path.exists(p):
        return None
    with open(p, "rb") as f:
        hdr = f.read(4096)
        i = (cx & 31) + (cz & 31) * 32
        loc = struct.unpack(">I", hdr[i * 4:i * 4 + 4])[0]
        if not loc:
            return None
        f.seek((loc >> 8) * 4096)
        ln, ct = struct.unpack(">IB", f.read(5))
        data = f.read(ln - 1)
    return zlib.decompress(data) if ct == 2 else data


def top_strings(buf, keys):
    """Cheap scan for top-level TAG_String values by name (Status) without full parse."""
    out = {}
    for k in keys:
        kb = b"\x08" + struct.pack(">H", len(k)) + k.encode()
        j = buf.find(kb)
        if j >= 0:
            j += len(kb)
            (n,) = struct.unpack(">H", buf[j:j + 2])
            out[k] = buf[j + 2:j + 2 + n].decode()
    return out


counts = collections.Counter()
rows = []
for cz in range(z0, z1 + 1):
    row = []
    for cx in range(x0, x1 + 1):
        try:
            b = read(cx, cz)
        except Exception:
            row.append("E"); counts["error"] += 1; continue
        if b is None:
            row.append("."); counts["absent"] += 1; continue
        st = top_strings(b, ["Status"]).get("Status", "?").replace("minecraft:", "")
        counts[st] += 1
        row.append("F" if st == "full" else st[:1].upper() if st != "initialize_light" else "I")
    rows.append(f"{cz:5d} " + "".join(row))
if not summary:
    print("      x " + str(x0) + " .. " + str(x1) + "   (F=full I=initialize_light L=light .=absent other=first letter)")
    print("\n".join(rows))
print(dict(counts))

if "--holes" in sys.argv:
    st = {}
    for cz in range(z0 - 1, z1 + 2):
        for cx in range(x0 - 1, x1 + 2):
            try:
                b = read(cx, cz)
                st[(cx, cz)] = None if b is None else top_strings(b, ["Status"]).get("Status", "?")
            except Exception:
                st[(cx, cz)] = "?"
    holes = []
    for cz in range(z0, z1 + 1):
        for cx in range(x0, x1 + 1):
            s = st[(cx, cz)]
            if s and s != "minecraft:full":
                nb = [st[(cx + dx, cz + dz)] for dx in (-1, 0, 1) for dz in (-1, 0, 1) if dx or dz]
                if all(n == "minecraft:full" for n in nb):
                    holes.append((cx, cz, s.replace("minecraft:", "")))
    print("interior holes:", len(holes))
    for h in holes:
        print("  chunk", h[0], h[1], "block", h[0] * 16 + 8, h[1] * 16 + 8, h[2])

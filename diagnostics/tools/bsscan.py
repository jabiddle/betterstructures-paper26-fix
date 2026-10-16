"""Predict BetterStructures 2.7.7 grid points and check whether a structure was actually placed at each.

Reimplements NewChunkLoadEvent.isValidStructurePosition (Java Random + world seed) and, for every predicted
chunk that exists on disk, looks within +/-2 chunks for a BetterStructures loot container: a chest, barrel or
trapped chest holding items but no vanilla LootTable. Read-only; safe to run on a live world, though chunks
still in memory only show up after a save (`save-all flush`).

usage: bsscan.py <dimension dir> <seed> <minChunkX> <maxChunkX> <minChunkZ> <maxChunkZ>
                 [--config plugins/BetterStructures/config.yml] [--baseline <older copy of the dimension dir>]

  <dimension dir>  folder containing region/ (Paper 26.x: world/dimensions/minecraft/overworld)
  <seed>           world seed (`/seed`)
  --config         read distance*/maxOffset* from BetterStructures' config.yml (defaults: plugin defaults)
  --baseline       an older copy of the world; chunks present there are reported as "old" instead of "new"

Output per structure type: predicted spots, then counts of not_generated / new_hit / new_miss / old_hit / old_miss.
"""
import argparse, os, re, struct, sys, zlib, collections

ap = argparse.ArgumentParser(usage=__doc__.split("usage: ")[1].split("\n\n")[0])
ap.add_argument("dimension")
ap.add_argument("seed", type=int)
ap.add_argument("box", type=int, nargs=4)
ap.add_argument("--config")
ap.add_argument("--baseline")
args = ap.parse_args()
cur, bak = args.dimension, args.baseline
SEED = args.seed
MINX, MAXX, MINZ, MAXZ = args.box
M64 = (1 << 64) - 1


def s64(v):
    v &= M64
    return v - (1 << 64) if v >> 63 else v


def s32(v):
    v &= 0xFFFFFFFF
    return v - (1 << 32) if v >> 31 else v


def jhash(s):
    h = 0
    for c in s:
        h = s32(31 * h + ord(c))
    return h


class JRandom:
    def __init__(self, seed):
        self.s = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)

    def next(self, bits):
        self.s = (self.s * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
        return s32(self.s >> (48 - bits))

    def nextInt(self, bound):
        r = self.next(31)
        m = bound - 1
        if bound & m == 0:
            return s32((bound * r) >> 31)
        u = r
        r = u % bound
        while s32(u - r + m) < 0:
            u = self.next(31)
            r = u % bound
        return r


def jdiv(a, b):  # Java truncating division
    q = abs(a) // abs(b)
    return q if (a >= 0) == (b > 0) else -q


# structure type -> (config distance key, default, config offset key, default); defaults match BetterStructures 2.7.7
CONFIG_KEYS = {
    "SURFACE": ("distanceSurface", 27, "maxOffsetSurface", 5),
    "UNDERGROUND_SHALLOW": ("distanceShallow", 22, "maxOffsetShallow", 5),
    "UNDERGROUND_DEEP": ("distanceDeep", 22, "maxOffsetDeep", 5),
    "SKY": ("distanceSky", 90, "maxOffsetSky", 5),
    "LIQUID_SURFACE": ("distanceLiquid", 60, "maxOffsetLiquid", 5),
    "DUNGEON": ("distanceDungeonV2", 80, "maxOffsetDungeonV2", 18),
}
_config = {}
if args.config:
    for m in re.finditer(r"^(\w+):\s*(-?\d+)\s*$", open(args.config, encoding="utf-8").read(), re.M):
        _config[m.group(1)] = int(m.group(2))
TYPES = {name: (_config.get(dk, dd), _config.get(ok, od)) for name, (dk, dd, ok, od) in CONFIG_KEYS.items()}


def points(name, g, off):
    type_seed = s64(SEED + s32(jhash(name) * 7919))
    out = set()
    for gx in range(jdiv(MINX - off, g) - 2, jdiv(MAXX + off, g) + 3):
        for gz in range(jdiv(MINZ - off, g) - 2, jdiv(MAXZ + off, g) + 3):
            bx, bz = gx * g, gz * g
            if gz % 2 != 0:  # Java: gz % 2 != 0 works the same for negatives here
                bx += g // 2
            r = JRandom(s64(type_seed ^ s64((bx << 32) | (bz & 0xFFFFFFFF))))
            ox = r.nextInt(2 * off + 1) - off
            oz = r.nextInt(2 * off + 1) - off
            cx, cz = bx + ox, bz + oz
            if MINX <= cx <= MAXX and MINZ <= cz <= MAXZ:
                out.add((cx, cz))
    return out


_hdr = {}


def header(root, rx, rz):
    if root is None:
        return None
    k = (root, rx, rz)
    if k not in _hdr:
        p = os.path.join(root, "region", f"r.{rx}.{rz}.mca")
        try:
            with open(p, "rb") as f:
                _hdr[k] = f.read(8192)
        except OSError:
            _hdr[k] = None
    return _hdr[k]


def exists(root, cx, cz):
    h = header(root, cx >> 5, cz >> 5)
    if not h or len(h) < 4096:
        return False
    i = (cx & 31) + (cz & 31) * 32
    return struct.unpack(">I", h[i * 4:i * 4 + 4])[0] != 0


def read_chunk(root, cx, cz):
    h = header(root, cx >> 5, cz >> 5)
    if not h:
        return None
    i = (cx & 31) + (cz & 31) * 32
    loc = struct.unpack(">I", h[i * 4:i * 4 + 4])[0]
    if not loc:
        return None
    with open(os.path.join(root, "region", f"r.{cx >> 5}.{cz >> 5}.mca"), "rb") as f:
        f.seek((loc >> 8) * 4096)
        ln, ct = struct.unpack(">IB", f.read(5))
        data = f.read(ln - 1)
    if ct == 2:
        return zlib.decompress(data)
    if ct == 1:
        import gzip
        return gzip.decompress(data)
    if ct == 3:
        return data
    raise ValueError(f"compression {ct}")


def parse(buf):
    pos = 0

    def rd(n):
        nonlocal pos
        b = buf[pos:pos + n]
        pos += n
        return b

    def string():
        (n,) = struct.unpack(">H", rd(2))
        return rd(n).decode("utf-8", "replace")

    def payload(t):
        if t == 1: return struct.unpack(">b", rd(1))[0]
        if t == 2: return struct.unpack(">h", rd(2))[0]
        if t == 3: return struct.unpack(">i", rd(4))[0]
        if t == 4: return struct.unpack(">q", rd(8))[0]
        if t == 5: rd(4); return None
        if t == 6: rd(8); return None
        if t == 7: (n,) = struct.unpack(">i", rd(4)); rd(n); return None
        if t == 8: return string()
        if t == 9:
            et, n = struct.unpack(">bi", rd(5))
            return [payload(et) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                tt = rd(1)[0]
                if tt == 0:
                    return d
                k = string()
                d[k] = payload(tt)
        if t == 11: (n,) = struct.unpack(">i", rd(4)); rd(4 * n); return None
        if t == 12: (n,) = struct.unpack(">i", rd(4)); rd(8 * n); return None
        raise ValueError(t)

    t = rd(1)[0]
    string()
    return payload(t)


SIG_IDS = ("minecraft:chest", "minecraft:barrel", "minecraft:trapped_chest")
_sig = {}


def bs_signature(cx, cz):
    """Count containers holding pre-filled items (BS loot) with no vanilla LootTable."""
    if (cx, cz) in _sig:
        return _sig[(cx, cz)]
    n = 0
    try:
        raw = read_chunk(cur, cx, cz)
        if raw:
            nbt = parse(raw)
            for be in nbt.get("block_entities") or []:
                if be.get("id") in SIG_IDS and be.get("Items") and "LootTable" not in be:
                    n += 1
    except Exception:  # chunk being rewritten etc.
        n = -1
    _sig[(cx, cz)] = n
    return n


summary = collections.OrderedDict()
for name, (g, off) in TYPES.items():
    pts = sorted(points(name, g, off))
    stats = collections.Counter()
    hits_list = []
    for cx, cz in pts:
        if not exists(cur, cx, cz):
            stats["not_generated"] += 1
            continue
        new = not exists(bak, cx, cz)
        hit = 0
        for dx in (-2, -1, 0, 1, 2):
            for dz in (-2, -1, 0, 1, 2):
                if exists(cur, cx + dx, cz + dz):
                    s = bs_signature(cx + dx, cz + dz)
                    if s > 0:
                        hit += s
        key = ("new" if new else "old") + ("_hit" if hit else "_miss")
        stats[key] += 1
        if hit and new:
            hits_list.append((cx * 16 + 8, cz * 16 + 8))
    summary[name] = (len(pts), dict(stats), hits_list[:12])

for k, v in summary.items():
    print(k, "predicted:", v[0], v[1], "sample hits (block x, z):", v[2])


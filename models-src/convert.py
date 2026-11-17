"""OBJ + MTL -> compact binary mesh for the mod (big-endian, already placed in item space)."""
import math, struct, sys

def mtl(path):
    mats, cur = {}, None
    for line in open(path):
        p = line.split()
        if not p: continue
        if p[0] == "newmtl": cur = p[1]; mats[cur] = {"kd": (1, 1, 1), "ke": (0, 0, 0)}
        elif p[0] == "Kd": mats[cur]["kd"] = tuple(map(float, p[1:4]))
        elif p[0] == "Ke": mats[cur]["ke"] = tuple(map(float, p[1:4]))
    return mats

def convert(obj, mtlpath, out, scale=0.85, shift=0.2):
    mats = mtl(mtlpath)
    names = list(mats)
    v, n, tris, cur = [], [], [], None
    for line in open(obj):
        p = line.split()
        if not p: continue
        if p[0] == "v": v.append(tuple(map(float, p[1:4])))
        elif p[0] == "vn": n.append(tuple(map(float, p[1:4])))
        elif p[0] == "usemtl": cur = names.index(p[1])
        elif p[0] == "f":
            idx = [tuple(int(x) if x else 0 for x in c.split("/")) for c in p[1:]]
            for k in range(1, len(idx) - 1):
                tris.append((cur, idx[0], idx[k], idx[k + 1]))
    c, s = math.cos(math.radians(-45)), math.sin(math.radians(-45))
    def place(x, y, z):
        x, y, z = x * scale, y * scale, z * scale
        return (x * c - y * s + shift, x * s + y * c + shift, z + 0.5)
    def rot(x, y, z):
        return (x * c - y * s, x * s + y * c, z)
    with open(out, "wb") as f:
        f.write(b"FPM1")
        f.write(struct.pack(">i", len(names)))
        for name in names:
            kd, ke = mats[name]["kd"], mats[name]["ke"]
            glow = max(ke)
            f.write(struct.pack(">4f", kd[0], kd[1], kd[2], glow))
        f.write(struct.pack(">i", len(tris)))
        lo = [9e9] * 3; hi = [-9e9] * 3
        for m, *corners in tris:
            f.write(struct.pack(">i", m))
            for vi, _, ni in corners:
                px, py, pz = place(*v[vi - 1])
                for i, val in enumerate((px, py, pz)):
                    lo[i] = min(lo[i], val); hi[i] = max(hi[i], val)
                nx, ny, nz = rot(*n[ni - 1]) if ni else (0, 0, 1)
                f.write(struct.pack(">7f", px, py, pz, nx, ny, nz, v[vi - 1][1]))
    print(out, len(names), "materials", len(tris), "tris", [round(x, 3) for x in lo], [round(x, 3) for x in hi])

if __name__ == "__main__":
    U = "/mnt/user-data/uploads/"
    O = "/home/claude/dev/src/main/resources/assets/foragingplanner/meshes/"
    import os; os.makedirs(O, exist_ok=True)
    convert(U + "e1b98ec7-ice_axe.obj", U + "b69935b1-ice_axe.mtl", O + "ice.bin")
    convert(U + "2b3801b7-nature_axe.obj", U + "9a342caf-nature_axe.mtl", O + "nature.bin")
    convert(U + "0052d185-lava_axe.obj", U + "aa2fe0f1-lava_axe.mtl", O + "lava.bin")

"""
Builds app/src/main/assets/migan_gen_512.onnx from the official MI-GAN ONNX *pipeline*.

The pipeline wraps the neural network with crop / resize / blur / scatter logic that blocks hardware
acceleration. This script cuts out just the network, fixes its input to 1x4x512x512, simplifies it and
folds its constant scalings into the convolution weights (exact, verified numerically below):

    Conv -> LeakyReLU -> Mul(c>0) -> Clip(+-256)   ==>   Conv'(c*W, c*b) -> LeakyReLU

usage: python make_generator.py migan_pipeline_v2.onnx migan_gen_512.onnx
requires: pip install onnx onnxsim onnxruntime numpy
"""
import sys
import numpy as np
import onnx
import onnx.utils
from onnx import numpy_helper, shape_inference

src, dst = sys.argv[1], sys.argv[2]
m = onnx.load(src)
first_conv = [n for n in m.graph.node if n.op_type == "Conv"][0]
IN = first_conv.input[0]
OUT = "/model/synthesis/b512/Add_1_output_0"
tmp = dst + ".tmp_gen.onnx"
onnx.utils.extract_model(src, tmp, [IN], [OUT])
m = onnx.load(tmp)

dims = m.graph.input[0].type.tensor_type.shape.dim
for d, v in zip(dims, [1, 4, 512, 512]):
    d.ClearField("dim_param")
    d.dim_value = v
for vi in list(m.graph.value_info):
    m.graph.value_info.remove(vi)
m = shape_inference.infer_shapes(m)
from onnxsim import simplify
m, ok = simplify(m, overwrite_input_shapes={IN: [1, 4, 512, 512]})
assert ok, "onnxsim failed"
reference = m  # simplified but not folded: used to verify the folding

def fold_pass(m, mode):
    """mode 'bias': drop no-op Clips, merge constant Add into Conv bias. mode 'scale': fold Mul(c) into Conv."""
    g = m.graph
    init = {i.name: i for i in g.initializer}
    consts = {i.name: numpy_helper.to_array(i) for i in g.initializer}
    for n in g.node:
        if n.op_type == "Constant":
            for a in n.attribute:
                if a.name == "value":
                    consts[n.output[0]] = numpy_helper.to_array(a.t)

    def scalar(name):
        v = consts.get(name)
        return None if v is None or v.size != 1 else float(v)

    producer = {o: n for n in g.node for o in n.output}
    users = {}
    for n in g.node:
        for i in n.input:
            users.setdefault(i, []).append(n)
    graph_outputs = {o.name for o in g.output}
    remove, rename = set(), {}
    refs = {}
    for n in g.node:
        for i in n.input:
            refs[i] = refs.get(i, 0) + 1

    def private(conv):
        """True if the conv's weight (and bias) are used by this conv only, so scaling them is safe."""
        ok = refs.get(conv.input[1], 0) == 1
        if len(conv.input) > 2:
            ok = ok and refs.get(conv.input[2], 0) == 1
        return ok

    def resolve(x):
        while x in rename:
            x = rename[x]
        return x

    count = 0
    if mode == "bias":
        for n in g.node:
            if n.op_type == "Clip" and len(n.input) == 3:
                lo, hi = scalar(n.input[1]), scalar(n.input[2])
                if lo is not None and hi is not None and lo <= -100 and hi >= 100:
                    remove.add(id(n))
                    rename[n.output[0]] = n.input[0]
        for n in g.node:
            if n.op_type != "Add" or id(n) in remove:
                continue
            for ci, ki in ((0, 1), (1, 0)):
                t, k = resolve(n.input[ci]), n.input[ki]
                p = producer.get(t)
                if k not in consts or p is None or p.op_type != "Conv" or len(users.get(t, [])) != 1 or t in graph_outputs:
                    continue
                if p.input[1] not in init:
                    continue
                cch = init[p.input[1]].dims[0]
                kv = consts[k]
                if kv.size != cch or kv.ndim not in (1, 4) or (kv.ndim == 4 and kv.shape[1] != cch):
                    continue
                kv = kv.reshape(-1).astype(np.float32)
                if len(p.input) > 2 and p.input[2] in init:
                    old = numpy_helper.to_array(init[p.input[2]])
                    init[p.input[2]].CopyFrom(numpy_helper.from_array((old + kv).astype(np.float32), p.input[2]))
                else:
                    name = p.output[0] + "_bias"
                    g.initializer.append(numpy_helper.from_array(kv, name))
                    p.input.append(name)
                remove.add(id(n))
                rename[n.output[0]] = t
                count += 1
                break
    else:
        def scale_conv(conv, c):
            w = init[conv.input[1]]
            w.CopyFrom(numpy_helper.from_array((numpy_helper.to_array(w) * c).astype(np.float32), w.name))
            if len(conv.input) > 2 and conv.input[2] in init:
                b = init[conv.input[2]]
                b.CopyFrom(numpy_helper.from_array((numpy_helper.to_array(b) * c).astype(np.float32), b.name))

        for n in g.node:
            if n.op_type != "Mul" or id(n) in remove:
                continue
            a, b = n.input
            c = scalar(b) if scalar(b) is not None else scalar(a)
            t = a if scalar(b) is not None else b
            if c is None:
                continue
            t = resolve(t)
            p = producer.get(t)
            if p is None or len(users.get(t, [])) != 1 or t in graph_outputs:
                continue
            conv = None
            add_k = None
            if p.op_type == "LeakyRelu" and c > 0:
                src_t = resolve(p.input[0])
                q = producer.get(src_t)
                if q is not None and q.op_type == "Conv" and len(users.get(src_t, [])) == 1 and private(q):
                    conv = q
                elif q is not None and q.op_type == "Add" and len(users.get(src_t, [])) == 1:
                    # LeakyReLU(Add(Conv, K)) * c == LeakyReLU(Add(c*Conv, c*K))
                    for ci, ki in ((0, 1), (1, 0)):
                        t2 = resolve(q.input[ci])
                        q2 = producer.get(t2)
                        if (q.input[ki] in consts and q2 is not None and q2.op_type == "Conv"
                                and len(users.get(t2, [])) == 1 and q2.input[1] in init and private(q2)):
                            conv = q2
                            add_k = (q, ki)
                            break
            elif p.op_type == "Conv" and private(p):
                conv = p
            if conv is None or conv.input[1] not in init:
                continue
            if add_k is not None:
                q, ki = add_k
                kname = q.input[ki]
                newk = numpy_helper.from_array((consts[kname] * c).astype(np.float32), kname + "_x%d" % count)
                g.initializer.append(newk)
                q.input[ki] = newk.name
            scale_conv(conv, c)
            remove.add(id(n))
            rename[n.output[0]] = n.input[0] if scalar(b) is not None else n.input[1]
            count += 1

    used = set()
    keep = []
    for n in g.node:
        if id(n) in remove:
            continue
        for k, i in enumerate(n.input):
            r = resolve(i)
            if r != i:
                n.input[k] = r
        keep.append(n)
    for o in g.output:
        if resolve(o.name) != o.name:
            raise SystemExit("graph output was folded away: " + o.name)
    del g.node[:]
    g.node.extend(keep)
    return count


m = onnx.ModelProto.FromString(m.SerializeToString())
n_bias = fold_pass(m, "bias")
n_scale = fold_pass(m, "scale")
m, ok = simplify(m, overwrite_input_shapes={IN: [1, 4, 512, 512]})
assert ok
folded = n_bias + n_scale
remove = set()

# verify against the unfolded reference
import onnxruntime as ort
x = np.random.default_rng(1).uniform(-1, 1, (1, 4, 512, 512)).astype(np.float32)
ra = ort.InferenceSession(reference.SerializeToString(), providers=["CPUExecutionProvider"]).run(None, {IN: x})[0]
rb = ort.InferenceSession(m.SerializeToString(), providers=["CPUExecutionProvider"]).run(None, {IN: x})[0]
diff = float(np.abs(ra - rb).max())
print(f"merged {n_bias} bias adds, folded {n_scale} scalings, nodes now {len(m.graph.node)}, max output diff {diff:.6f}")
assert diff < 1e-2, "verification failed"
onnx.save(m, dst)
print("saved", dst)

# NPU-friendly variant: Android NNAPI has no LeakyRelu, but it does support PRelu (identical math here).
m2 = shape_inference.infer_shapes(onnx.ModelProto.FromString(m.SerializeToString()))
shapes = {vi.name: [d.dim_value for d in vi.type.tensor_type.shape.dim] for vi in m2.graph.value_info}
g2 = m2.graph
new_nodes, extra = [], []
for n in g2.node:
    if n.op_type == "LeakyRelu":
        alpha = [a.f for a in n.attribute if a.name == "alpha"][0]
        sh = shapes.get(n.input[0])
        if not sh or len(sh) != 4:
            raise SystemExit("unknown shape for " + n.input[0])
        slope = numpy_helper.from_array(np.full((sh[1], 1, 1), alpha, dtype=np.float32), n.output[0] + "_slope")
        extra.append(slope)
        new_nodes.append(onnx.helper.make_node("PRelu", [n.input[0], slope.name], list(n.output), name=n.name + "_prelu"))
    else:
        new_nodes.append(n)
del g2.node[:]
g2.node.extend(new_nodes)
g2.initializer.extend(extra)
while len(g2.value_info):
    g2.value_info.pop()
rc = ort.InferenceSession(m2.SerializeToString(), providers=["CPUExecutionProvider"]).run(None, {IN: x})[0]
d2 = float(np.abs(rb - rc).max())
print(f"NPU variant: {len(extra)} LeakyRelu -> PRelu, max output diff {d2:.6f}")
assert d2 < 1e-2
npu = dst[:-5] + "_npu.onnx" if dst.endswith(".onnx") else dst + "_npu"
onnx.save(m2, npu)
print("saved", npu)
import os
os.remove(tmp)

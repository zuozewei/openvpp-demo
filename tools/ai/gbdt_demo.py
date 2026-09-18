#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GBDT 训推链演示 —— 专栏第 47 篇《GBDT 预测训推链：Python 副车与 Java 主战》配套代码。
零第三方依赖（纯标准库），复现"物理基线 + GBDT 学残差 + 副车推理服务"的完整链路：
  1. 物理侧：太阳几何 solar_shape / 组件温度 / 温度降额（与 Java 侧 PvPhysics 逐系数一致，
     公式是两个语言间的"契约"）；
  2. 训练侧：合成光伏功率 = 物理基线 + 残差扰动，用微型梯度提升树（决策桩 + 平方误差）
     只学残差 y = p - baseline；带质量闸门（样本 < 200 拒训）；
  3. 推理侧：http.server 副车（默认 127.0.0.1:43317），GET /health、POST /predict，
     physics_residual 模式输出 pred = baseline + residual，夜间清零、装机截断；
  4. 自检：python gbdt_demo.py --selfcheck 对比"仅物理基线"与"物理+残差模型"的 MAE，
     并校验特征键序契约。

用法：
  python gbdt_demo.py --selfcheck        # 本地自检（不启服务）
  python gbdt_demo.py                    # 启动推理副车（配合 Java GbdtClient 联调）
"""
import json
import math
import random
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# —— 跨语言契约：特征键序（训练与推理必须同一顺序，靠纪律保证） ——
FEATURE_NAMES = [
    "hour", "minute", "weekday", "is_weekend", "solar_shape", "rated_kw",
    "irr_factor", "temp", "lag_same_hm", "lag_recent",
    "irradiance_wm2", "module_temp", "wind_speed",
]

GBDT_PORT = 43317
DEMO_LAT, DEMO_LON = 39.12, 117.20


# ———————————————— 物理侧（与 Java com.openvpp.assessment.predict.PvPhysics 对齐） ————————————————

def solar_shape(day_of_year, hour, lat_deg=DEMO_LAT, lon_deg=DEMO_LON, seed=0):
    """太阳高度角形状（0~1），夜间硬性 0；站点微扰使曲线可复现地略有差异。"""
    lat = math.radians(lat_deg)
    decl = math.radians(23.45 * math.sin(math.radians(360.0 / 365 * (day_of_year - 81))))
    noon = 12 + (120 - lon_deg) / 15
    ha = math.radians(15 * (hour - noon))
    sin_el = math.sin(lat) * math.sin(decl) + math.cos(lat) * math.cos(decl) * math.cos(ha)
    if sin_el <= 0:
        return 0.0
    wobble = 0.025 * math.sin((hour * 3.1 + seed % 97) * 0.6)
    return max(0.0, min(1.0, sin_el * 0.95 + wobble))


def module_temp(ambient_c, ghi_wm2, wind_ms):
    """组件温度 = 环温 + 8.0 + 辐照比×12.0 − 0.4×max(0, 风速−2)。"""
    return ambient_c + 8.0 + (ghi_wm2 / 1000.0) * 12.0 - 0.4 * max(0.0, wind_ms - 2)


def temp_derate(module_temp_c):
    """组件温度降额：≤25℃ 为 1.0，每升 1℃ 降 0.4%，下限 0.85。"""
    if module_temp_c <= 25:
        return 1.0
    return max(0.85, 1.0 - (module_temp_c - 25) * 0.004)


def physics_baseline(feat):
    """光伏物理基线 = 装机 × (GHI/1000) × 温度降额。"""
    return feat["rated_kw"] * (feat["irradiance_wm2"] / 1000.0) * temp_derate(feat["module_temp"])


# ———————————————— 训练侧：微型梯度提升树（决策桩）只学残差 ————————————————

class Stump:
    """单特征阈值决策桩：x[f] <= t ? left : right。"""

    __slots__ = ("f", "t", "left", "right")

    def __init__(self, f, t, left, right):
        self.f, self.t, self.left, self.right = f, t, left, right

    def predict_one(self, x):
        return self.left if x[self.f] <= self.t else self.right


def fit_stump(xs, grad, idx, candidates):
    """对指定特征穷举候选阈值，返回平方误差下降最大的桩。"""
    best = None
    for f in idx:
        vals = sorted(set(x[f] for x in xs))
        for i in range(len(vals) - 1):
            t = (vals[i] + vals[i + 1]) / 2
            left = [g for x, g in zip(xs, grad) if x[f] <= t]
            right = [g for x, g in zip(xs, grad) if x[f] > t]
            if not left or not right:
                continue
            ml = sum(left) / len(left)
            mr = sum(right) / len(right)
            loss = sum(g * g for g in left) + sum(g * g for g in right)
            if best is None or loss < best[0]:
                best = (loss, Stump(f, t, ml, mr))
    return best[1] if best else Stump(0, 1e9, 0.0, 0.0)


class TinyGbr:
    """微型梯度提升回归：残差学习 + 平方误差 + 固定学习率（教学复现 HistGBR 的最小内核）。"""

    def __init__(self, n_iter=40, lr=0.3):
        self.n_iter, self.lr = n_iter, lr
        self.trees = []
        self.base = 0.0

    def fit(self, feats, ys):
        assert len(feats) >= 200, "训练质量闸门：样本 < 200 拒训"
        xs = [[f.get(k, 0) for k in FEATURE_NAMES] for f in feats]   # dict → 契约序向量
        self.base = sum(ys) / len(ys)
        pred = [self.base] * len(ys)
        idx = list(range(len(FEATURE_NAMES)))
        for _ in range(self.n_iter):
            grad = [y - p for y, p in zip(ys, pred)]
            stump = fit_stump(xs, grad, idx, None)
            self.trees.append(stump)
            pred = [p + self.lr * stump.predict_one(x) for x, p in zip(xs, pred)]
        return self

    def predict_one(self, feat):
        x = [feat.get(k, 0) for k in FEATURE_NAMES]
        out = self.base
        for stump in self.trees:
            out += self.lr * stump.predict_one(x)
        return out


def make_training_set(n_days=28, rated_kw=240.0, seed=42):
    """合成训练集：功率 = 物理基线 + 可复现残差（含滞后/温度效应），15 分钟粒。"""
    rng = random.Random(seed)
    xs, ys = [], []
    for d in range(n_days):
        day = 150 + d
        irr_factor = rng.choice([1.0, 0.78, 0.58])
        temp = rng.uniform(18, 34)
        wind = rng.uniform(0.5, 5.0)
        lag = 0.0
        for t in range(96):
            hour = t * 0.25
            shape = solar_shape(day, hour, seed=7)
            if shape <= 0:
                lag = 0.0
                continue
            ghi = 1000 * shape * irr_factor
            mtemp = module_temp(temp, ghi, wind)
            feat = {
                "hour": int(hour), "minute": (t % 4) * 15, "weekday": (day % 7) + 1,
                "is_weekend": 1 if day % 7 in (5, 6) else 0,
                "solar_shape": round(shape, 4), "rated_kw": rated_kw,
                "irr_factor": irr_factor, "temp": temp,
                "lag_same_hm": lag, "lag_recent": lag,
                "irradiance_wm2": round(ghi, 2), "module_temp": round(mtemp, 2),
                "wind_speed": wind,
            }
            # 真实功率 = 基线 + 残差（温度敏感 + 滞后惯性 + 噪声）——模型要学的就是它
            residual = (temp - 25) * 0.8 + lag * 0.05 + rng.gauss(0, 1.5)
            power = max(0.0, min(rated_kw, physics_baseline(feat) + residual))
            xs.append(feat)
            ys.append(power - physics_baseline(feat))   # 标签是残差，不是功率
            lag = power
    return xs, ys


# ———————————————— 推理副车 ————————————————

MODEL = None


def load_models():
    global MODEL
    xs, ys = make_training_set()
    MODEL = TinyGbr().fit(xs, ys)


def predict_point(point):
    """physics_residual 推理：pred = 基线 + 残差；夜间清零、装机截断。"""
    shape = point.get("solar_shape", 0)
    if shape <= 0:
        return 0.0
    residual = MODEL.predict_one(point)
    rated = point.get("rated_kw", 0)
    pred = physics_baseline(point) + residual
    return round(max(0.0, min(rated, pred)), 2)


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"code": 200, "ready": MODEL is not None,
                             "algorithm": "GBDT_PV_V1",
                             "feature_names": FEATURE_NAMES, "trained_at": "demo"})
        else:
            self._send(404, {"code": 404})

    def do_POST(self):
        if self.path != "/predict":
            self._send(404, {"code": 404})
            return
        length = int(self.headers.get("Content-Length", 0))
        try:
            req = json.loads(self.rfile.read(length))
            points = req["points"]
        except Exception:
            self._send(400, {"code": 400, "msg": "bad json"})
            return
        if MODEL is None:
            self._send(503, {"code": 503, "msg": "model not loaded"})
            return
        # 特征键序契约自检：缺键直接拒（/health 只回显不校验，这里是最后一道闸）
        for p in points:
            missing = [k for k in FEATURE_NAMES if k not in p]
            if missing:
                self._send(400, {"code": 400, "msg": "missing keys: %s" % missing})
                return
        self._send(200, {"code": 200, "algorithm": "GBDT_PV_V1",
                         "predictKw": [predict_point(p) for p in points]})

    def log_message(self, fmt, *args):
        print("[gbdt-demo] %s" % (fmt % args))


# ———————————————— 自检 ————————————————

def selfcheck():
    xs, ys = make_training_set()
    split = int(len(ys) * 0.85)          # 按时间顺序 85/15（随机切分会泄漏未来）
    model = TinyGbr().fit(xs[:split], ys[:split])

    def mae(use_model):
        errs, n = 0.0, 0
        for x, y in zip(xs[split:], ys[split:]):
            if x["solar_shape"] <= 0:
                continue
            pred_res = model.predict_one(x) if use_model else 0.0
            errs += abs((physics_baseline(x) + pred_res) - (physics_baseline(x) + y))
            n += 1
        return errs / max(1, n)

    base_mae, model_mae = mae(False), mae(True)
    print("验证点数 %d（后 15%% 时序切分）" % len(xs[split:]))
    print("仅物理基线  MAE = %.3f kW" % base_mae)
    print("物理+残差模型 MAE = %.3f kW" % model_mae)
    print("残差模型改进  = %.1f%%" % ((1 - model_mae / base_mae) * 100))
    assert model_mae < base_mae, "学残差必须优于不学"
    # 契约自检：正午有太阳、夜间为零
    noon = solar_shape(180, 12.2)
    night = solar_shape(180, 23.0)
    assert noon > 0.5 and night == 0.0, "太阳几何契约异常"
    print("太阳几何契约自检通过（正午 shape=%.3f，夜间=0）" % noon)
    print("selfcheck 全部通过")


if __name__ == "__main__":
    import sys
    if "--selfcheck" in sys.argv:
        selfcheck()
    else:
        load_models()
        print("GBDT 推理副车启动: http://127.0.0.1:%d （/health /predict）" % GBDT_PORT)
        ThreadingHTTPServer(("127.0.0.1", GBDT_PORT), Handler).serve_forever()

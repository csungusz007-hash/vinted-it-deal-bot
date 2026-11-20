import sys, os, json, tempfile  # futtatás: python test_engine.py
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vinted_bot as core
d = tempfile.mkdtemp()
cfg = {"searches": [{"name": "GPU", "queries": {"all": ["gpu"]}, "target_price_huf": 50000}], "to_huf": {"PLN": 90}}
json.dump(cfg, open(os.path.join(d, "config.json"), "w"))
core.CONFIG_PATH, core.STATE_PATH = os.path.join(d, "config.json"), os.path.join(d, "state.json")
import app
app.DEALS_PATH, app.WATCH_PATH, app.HISTORY_PATH = (os.path.join(d, n) for n in ("a.json", "w.json", "h.json"))
app.windows_toast = lambda *a: None
e = app.Engine()
item = lambda i, p: {"id": i, "title": "RTX card", "price": {"amount": str(p), "currencyCode": "PLN"}, "user": {"id": 1}}
s = cfg["searches"][0]
e._process("pl", s, "gpu", [item(1, 700)])            # first round: seeds (700 PLN = 63000 Ft, above target)
e._process("pl", s, "gpu", [item(1, 700)])            # same price: nothing
assert not e.deals
e._process("pl", s, "gpu", [item(1, 500)])            # dropped to 45000 Ft: under target -> deal
assert len(e.deals) == 1 and e.deals[0]["drop_from"] == 63000, e.deals
e._process("pl", s, "gpu", [item(1, 500)])            # no repeat
e._process("pl", s, "gpu", [item(1, 450)])            # further drop -> replaces, still one card
assert len(e.deals) == 1 and e.deals[0]["total_huf"] == 40500
# watchlist apply
app.Api(e).watch_add("1")
e._apply_watch(e.watch[0], {"gone": False, "closed": False, "reserved": True, "price": 400.0})
assert e.watch[0]["reserved"] and e.watch[0]["now_label"] == "400 PLN"
e._apply_watch(e.watch[0], {"gone": False, "closed": True, "reserved": False, "price": None})
assert e.watch[0]["sold"]
e._on_wait("pl", 30, 429); assert e.status["pl"]["state"] == "wait"
assert "deals" not in e.snapshot(e.seq) and "deals" in e.snapshot(-1)
print("engine OK")

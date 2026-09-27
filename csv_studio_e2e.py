#!/usr/bin/env python3
"""CSV Studio E2E: app API + AI stream tool calls.

Usage: python3 csv_studio_e2e.py
Prereq: app on 127.0.0.1:8102, harness on 127.0.0.1:8090,
        plugin dsh-java-csv-plugin installed & activated,
        demo csv at ~/.dsh-java-csv/demo/orders.csv
"""
import json
import os
import sys
import time
import urllib.request

APP = "http://127.0.0.1:8102"
CSV = os.path.expanduser("~/.dsh-java-csv/demo/orders.csv")
results = []


def call(path, payload):
    req = urllib.request.Request(APP + path, data=json.dumps(payload).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def stream_once(agent_id, message, csv_path):
    body = json.dumps({"agentId": agent_id, "message": message, "csvPath": csv_path}).encode()
    req = urllib.request.Request(APP + "/api/assistant/stream", data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    content, tools, events = "", [], []
    try:
        with urllib.request.urlopen(req, timeout=200) as resp:
            event, data_buf = "message", []
            for raw in resp.read().decode("utf-8", "ignore").splitlines() + [""]:
                if raw.startswith("event:"):
                    event = raw[6:].strip()
                elif raw.startswith("data:"):
                    data_buf.append(raw[5:].strip())
                elif not raw and data_buf:
                    try:
                        payload = json.loads("\n".join(data_buf))
                        events.append(event)
                        if event == "chunk":
                            content += payload.get("content", "")
                        if event == "step_break" and payload.get("toolName"):
                            tools.append(payload["toolName"])
                    except json.JSONDecodeError:
                        pass
                    event, data_buf = "message", []
    except Exception as e:  # noqa: BLE001
        content += f"\nSTREAM-ERROR: {e}"
    return content, sorted(set(tools)), events


def case(name):
    def deco(fn):
        def wrapped():
            start = time.time()
            try:
                detail = fn()
                results.append((name, True, detail, time.time() - start))
                print(f"PASS  {name}  ({time.time()-start:.1f}s)  {detail}")
            except AssertionError as e:
                results.append((name, False, str(e), time.time() - start))
                print(f"FAIL  {name}  ({time.time()-start:.1f}s)  {e}")
            except Exception as e:  # noqa: BLE001
                results.append((name, False, f"{type(e).__name__}: {e}", time.time() - start))
                print(f"FAIL  {name}  ({time.time()-start:.1f}s)  {type(e).__name__}: {e}")
        return wrapped
    return deco


@case("T1 数据画像")
def t1():
    status, body = call("/api/csv/profile", {"path": CSV})
    assert status == 200, f"profile {status}: {body}"
    assert body.get("dataRows") == 15, f"dataRows={body.get('dataRows')}"
    cols = {c["name"]: c for c in body.get("columns", [])}
    assert cols["amount"]["type"] == "decimal", f"amount type={cols['amount']['type']}"
    assert cols["order_date"]["type"] == "date", f"date type={cols['order_date']['type']}"
    assert cols["amount"]["numeric"]["max"] == 15200.0, "max wrong"
    return f"rows=15 cols={len(cols)} amount=decimal"


@case("T2 分组汇总")
def t2():
    status, body = call("/api/csv/pivot?groupBy=region&valueColumn=amount", {"path": CSV})
    assert status == 200, f"pivot {status}: {body}"
    groups = {g["region"]: g for g in body.get("groups", [])}
    assert groups["华东"]["count"] == 5, f"华东={groups.get('华东')}"
    assert abs(groups["华东"]["sum"] - 21618.9) < 0.01, f"华东 sum={groups['华东']['sum']}"
    return f"groups={len(groups)} 华东sum={groups['华东']['sum']}"


@case("T3 离群值检测")
def t3():
    status, body = call("/api/csv/outliers?column=amount", {"path": CSV})
    assert status == 200, f"outliers {status}: {body}"
    values = [o["value"] for o in body.get("outliers", [])]
    assert 15200.0 in values, f"outliers={values}"
    assert body.get("outlierCount") == 2, f"count={body.get('outlierCount')}"
    return f"outliers={values}"


@case("T4 清洗计划与头部预览")
def t4():
    status, plan = call("/api/csv/clean-plan", {"path": CSV})
    assert status == 200 and "actions" in plan, f"plan {status}: {plan}"
    status, head = call("/api/csv/head", {"path": CSV})
    assert status == 200 and len(head.get("header", [])) == 5, f"head {status}"
    assert len(head.get("rows", [])) == 10, f"rows={len(head.get('rows', []))}"
    return f"actions={len(plan['actions'])} header=5 rows=10"


@case("T5 AI 对话调用 CSV 工具")
def t5():
    content, tools, events = stream_once("e2e-csv-t5", "哪个地区的销售额最高？异常订单有几单？用工具查证后回答", CSV)
    assert any("csv_" in t for t in tools), f"no csv tool called, tools={tools}"
    assert "chunk" in events, "no chunk events"
    assert len(content) > 20, f"content too short: {content[:80]}"
    return f"tools={tools} chars={len(content)}"


def main():
    t1(); t2(); t3(); t4(); t5()
    passed = sum(1 for _, ok, _, _ in results if ok)
    print(f"\n{passed}/{len(results)} PASS")
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()

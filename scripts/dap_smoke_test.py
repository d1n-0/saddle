#!/usr/bin/env python3
"""Smoke test for the Saddle DAP server.

Prerequisites: a Minecraft server with the Saddle mod is running (e.g.
`./gradlew runServer`) and its DAP port is reachable. The script installs the
test datapack into the world, reloads, and exercises the full debug loop:
breakpoints, stopped events, stack traces, variables, stepping, pause,
evaluate and the custom minecraft/* introspection requests.

Usage: python3 scripts/dap_smoke_test.py [--host H] [--port P] [--world DIR]
"""

import argparse
import json
import os
import queue
import shutil
import socket
import sys
import threading
import time

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class DapError(Exception):
    pass


class DapClient:
    def __init__(self, host, port, timeout=15.0):
        self.sock = socket.create_connection((host, port), timeout=10)
        self.sock.settimeout(None)
        self.timeout = timeout
        self.seq = 0
        self.events = queue.Queue()
        self.responses = {}
        self.responses_cv = threading.Condition()
        self.reader = threading.Thread(target=self._read_loop, daemon=True)
        self.reader.start()

    def _read_loop(self):
        buf = b""
        try:
            while True:
                while b"\r\n\r\n" not in buf:
                    chunk = self.sock.recv(65536)
                    if not chunk:
                        return
                    buf += chunk
                header, _, buf = buf.partition(b"\r\n\r\n")
                length = None
                for line in header.decode("ascii").split("\r\n"):
                    key, _, value = line.partition(":")
                    if key.strip().lower() == "content-length":
                        length = int(value.strip())
                if length is None:
                    raise DapError("missing Content-Length")
                while len(buf) < length:
                    chunk = self.sock.recv(65536)
                    if not chunk:
                        return
                    buf += chunk
                body, buf = buf[:length], buf[length:]
                msg = json.loads(body.decode("utf-8"))
                if msg.get("type") == "response":
                    with self.responses_cv:
                        self.responses[msg["request_seq"]] = msg
                        self.responses_cv.notify_all()
                elif msg.get("type") == "event":
                    self.events.put(msg)
        except OSError:
            pass

    def request(self, command, arguments=None, expect_success=True, timeout=None):
        self.seq += 1
        req_seq = self.seq
        msg = {"type": "request", "seq": req_seq, "command": command}
        if arguments is not None:
            msg["arguments"] = arguments
        body = json.dumps(msg).encode("utf-8")
        self.sock.sendall(b"Content-Length: %d\r\n\r\n" % len(body) + body)
        deadline = time.monotonic() + (timeout or self.timeout)
        with self.responses_cv:
            while req_seq not in self.responses:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise DapError(f"timeout waiting for response to '{command}'")
                self.responses_cv.wait(remaining)
            resp = self.responses.pop(req_seq)
        if expect_success and not resp.get("success"):
            raise DapError(f"request '{command}' failed: {resp.get('message')}")
        return resp

    def wait_event(self, name, timeout=15.0):
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise DapError(f"timeout waiting for event '{name}'")
            try:
                event = self.events.get(timeout=remaining)
            except queue.Empty:
                continue
            if event.get("event") == name:
                return event

    def expect_no_event(self, name, within):
        deadline = time.monotonic() + within
        leftovers = []
        try:
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                event = self.events.get(timeout=remaining)
                if event.get("event") == name:
                    raise DapError(f"unexpected event '{name}': {event}")
                leftovers.append(event)
        except queue.Empty:
            pass
        for event in leftovers:
            self.events.put(event)

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


passed = 0


def check(condition, label, detail=""):
    global passed
    if not condition:
        raise DapError(f"FAIL: {label} {detail}".rstrip())
    passed += 1
    print(f"  ok: {label}")


def install_datapack(world_dir):
    src = os.path.join(REPO_ROOT, "scripts", "test-datapack")
    dst = os.path.join(world_dir, "datapacks", "saddle-test")
    if os.path.isdir(dst):
        shutil.rmtree(dst)
    shutil.copytree(src, dst)
    return dst


def evaluate(client, expression, timeout=12.0):
    resp = client.request("evaluate", {"expression": expression, "context": "repl"}, timeout=timeout)
    return resp["body"].get("result", "")


def top_frame(client):
    frames = client.request("stackTrace", {"threadId": 1})["body"]["stackFrames"]
    if not frames:
        raise DapError("empty stack trace")
    return frames


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=16352)
    parser.add_argument("--world", default=os.path.join(REPO_ROOT, "run", "world"))
    args = parser.parse_args()

    print("== setup ==")
    pack_dir = install_datapack(args.world)
    main_fn = os.path.join(pack_dir, "data", "saddle_test", "function", "main.mcfunction")
    print(f"  datapack installed at {pack_dir}")

    client = DapClient(args.host, args.port)
    try:
        print("== handshake ==")
        resp = client.request("initialize", {"adapterID": "saddle", "clientID": "smoke-test"})
        check(resp["body"].get("supportsConfigurationDoneRequest") is True, "initialize capabilities")
        client.wait_event("initialized", timeout=5)
        check(True, "initialized event received")
        client.request("attach", {})

        print("== load test datapack ==")
        evaluate(client, "reload")
        # Wait for the reload to finish: breakpoints verify once the function is parsed.
        deadline = time.monotonic() + 30
        bp_body = None
        while time.monotonic() < deadline:
            bp_body = client.request("setBreakpoints", {
                "source": {"path": main_fn},
                "breakpoints": [{"line": 1}],
            })["body"]
            if bp_body["breakpoints"][0].get("verified"):
                break
            time.sleep(0.5)
        check(bp_body["breakpoints"][0].get("verified") is True
              and bp_body["breakpoints"][0]["line"] == 2,
              "comment breakpoint shifts to line 2", str(bp_body))

        bp_body = client.request("setBreakpoints", {
            "source": {"path": main_fn},
            "breakpoints": [{"line": 4}],
        })["body"]
        check(bp_body["breakpoints"][0].get("verified") is True
              and bp_body["breakpoints"][0]["line"] == 4,
              "breakpoint on command line verified")

        locations = client.request("breakpointLocations", {
            "source": {"path": main_fn}, "line": 1, "endLine": 7,
        })["body"]["breakpoints"]
        check([l["line"] for l in locations] == [2, 3, 4, 5, 6, 7], "breakpointLocations", str(locations))

        client.request("configurationDone", {})

        print("== breakpoint hit ==")
        evaluate(client, "function saddle_test:main")
        stopped = client.wait_event("stopped")
        check(stopped["body"]["reason"] == "breakpoint", "stopped reason is breakpoint", str(stopped["body"]))
        check(stopped["body"]["threadId"] == 1, "stopped threadId")

        threads = client.request("threads")["body"]["threads"]
        check(len(threads) == 1 and threads[0]["id"] == 1, "threads request")

        frames = top_frame(client)
        check(frames[0]["line"] == 4, "stopped at line 4", str(frames[0]))
        check(frames[0]["source"].get("path", "").endswith("main.mcfunction"),
              "top frame maps to main.mcfunction", str(frames[0]["source"]))

        scopes = client.request("scopes", {"frameId": frames[0]["id"]})["body"]["scopes"]
        check([s["name"] for s in scopes] == ["Executor", "Command", "Scoreboard", "Storage"],
              "scopes request", str(scopes))
        variables = client.request("variables",
                                   {"variablesReference": scopes[0]["variablesReference"]})["body"]["variables"]
        names = {v["name"] for v in variables}
        check("dimension" in names and "position" in names, "executor variables", str(names))

        print("== evaluate while suspended ==")
        # The stop happens before line 4 runs, so write and read a score to
        # prove commands execute in the isolated context while suspended.
        evaluate(client, "scoreboard players set target saddle_dbg 7")
        result = evaluate(client, "scoreboard players get target saddle_dbg")
        check("7" in result, "evaluate runs commands while suspended", result)

        print("== stepping ==")
        client.request("next", {"threadId": 1})
        stopped = client.wait_event("stopped")
        check(stopped["body"]["reason"] == "step", "step-over stop reason")
        frames = top_frame(client)
        check(frames[0]["line"] == 5, "step over landed on line 5", str(frames[0]))

        client.request("stepIn", {"threadId": 1})
        client.wait_event("stopped")
        frames = top_frame(client)
        check(frames[0]["name"] == "saddle_test:sub" and frames[0]["line"] == 1,
              "step in entered saddle_test:sub line 1", str(frames[0]))
        check(len(frames) >= 2 and frames[1]["line"] == 5, "caller frame visible at line 5", str(frames))

        source_ref = frames[0]["source"].get("sourceReference")
        if source_ref:
            content = client.request("source", {"sourceReference": source_ref})["body"]["content"]
            check("[saddle-test] sub" in content, "source request returns function text")

        client.request("stepOut", {"threadId": 1})
        client.wait_event("stopped")
        frames = top_frame(client)
        check(frames[0]["name"] == "saddle_test:main" and frames[0]["line"] == 6,
              "step out returned to main line 6", str(frames[0]))

        print("== time travel debugging ==")
        client.request("stepBack", {"threadId": 1})
        stopped = client.wait_event("stopped")
        check("Time travel" in stopped["body"].get("description", ""), "time-travel description")
        frames = top_frame(client)
        check(frames[0]["name"] == "saddle_test:sub" and frames[0]["line"] == 2,
              "step back lands on sub line 2", str(frames[0]))
        check(len(frames) >= 2 and frames[1]["line"] == 5, "recorded caller frame at line 5", str(frames))

        scopes = client.request("scopes", {"frameId": frames[0]["id"]})["body"]["scopes"]
        names = [s["name"] for s in scopes]
        check("Scoreboard (recorded)" in names and "Storage (recorded)" in names,
              "recorded scopes present", str(names))
        sb_ref = next(s["variablesReference"] for s in scopes if s["name"] == "Scoreboard (recorded)")
        objectives = client.request("variables", {"variablesReference": sb_ref})["body"]["variables"]
        dbg = next((o for o in objectives if o["name"] == "saddle_dbg"), None)
        check(dbg is not None, "recorded scoreboard objective", str(objectives))
        scores = client.request("variables",
                                {"variablesReference": dbg["variablesReference"]})["body"]["variables"]
        target = next((s for s in scores if s["name"] == "target"), None)
        check(target is not None and target["value"] == "42",
              "score reconstructed as of the past step", str(scores))

        client.request("stepBack", {"threadId": 1})
        client.wait_event("stopped")
        frames = top_frame(client)
        check(frames[0]["name"] == "saddle_test:sub" and frames[0]["line"] == 1,
              "second step back to sub line 1", str(frames[0]))

        client.request("reverseContinue", {"threadId": 1})
        stopped = client.wait_event("stopped")
        check(stopped["body"]["reason"] == "breakpoint", "reverse continue stops at breakpoint record")
        frames = top_frame(client)
        check(frames[0]["name"] == "saddle_test:main" and frames[0]["line"] == 4,
              "reverse continue reached main line 4", str(frames[0]))

        client.request("next", {"threadId": 1})
        client.wait_event("stopped")
        frames = top_frame(client)
        check(frames[0]["line"] == 5, "forward step in history to line 5", str(frames[0]))
        client.request("next", {"threadId": 1})
        client.wait_event("stopped")
        frames = top_frame(client)
        check(frames[0]["line"] == 6, "forward step returns to the present", str(frames[0]))

        trace = client.request("saddle/trace", {"count": 50})["body"]["steps"]
        check(len(trace) >= 5 and any(s["function"] == "saddle_test:sub" for s in trace),
              "execution trace request", f"{len(trace)} steps")

        print("== storage time travel ==")
        # ttd.mcfunction writes v:1 (line 2) then v:2 (line 3). Stopped at
        # line 4, one step back must show the storage as it was before line 3
        # ran — v:1 — regardless of what previous runs left behind.
        client.request("continue", {"threadId": 1})
        time.sleep(0.5)
        ttd_fn = os.path.join(pack_dir, "data", "saddle_test", "function", "ttd.mcfunction")
        client.request("setBreakpoints", {"source": {"path": ttd_fn}, "breakpoints": [{"line": 4}]})
        evaluate(client, "function saddle_test:ttd")
        client.wait_event("stopped")
        result = evaluate(client, "data get storage saddle_test:ttd v")
        check("2" in result, "live storage has the final value", result)
        client.request("stepBack", {"threadId": 1})
        client.wait_event("stopped")
        scopes = client.request("scopes", {"frameId": 1})["body"]["scopes"]
        rec_ref = next(s["variablesReference"] for s in scopes if s["name"] == "Storage (recorded)")
        stores = client.request("variables", {"variablesReference": rec_ref})["body"]["variables"]
        ttd_store = next((s for s in stores if s["name"] == "saddle_test:ttd"), None)
        check(ttd_store is not None, "recorded storage id present", str(stores))
        values = client.request("variables",
                                {"variablesReference": ttd_store["variablesReference"]})["body"]["variables"]
        v = next((x for x in values if x["name"] == "v"), None)
        check(v is not None and v["value"] == "1",
              "storage reconstructed to pre-write value", str(values))
        client.request("continue", {"threadId": 1})
        time.sleep(0.3)
        client.request("setBreakpoints", {"source": {"path": ttd_fn}, "breakpoints": []})
        # Re-arm the main breakpoint scenario for the following sections.
        client.request("setBreakpoints", {"source": {"path": main_fn}, "breakpoints": [{"line": 4}]})
        evaluate(client, "function saddle_test:main")
        client.wait_event("stopped")
        client.request("next", {"threadId": 1})
        client.wait_event("stopped")
        client.request("stepIn", {"threadId": 1})
        client.wait_event("stopped")
        client.request("stepOut", {"threadId": 1})
        client.wait_event("stopped")
        frames = top_frame(client)
        check(frames[0]["line"] == 6, "re-armed at main line 6 for continue section", str(frames[0]))

        print("== continue ==")
        client.request("continue", {"threadId": 1})
        time.sleep(0.5)
        result = evaluate(client, "scoreboard players get target saddle_dbg")
        check("53" in result, "function completed, score is 53", result)

        print("== breakpoints cleared ==")
        client.request("setBreakpoints", {"source": {"path": main_fn}, "breakpoints": []})
        evaluate(client, "function saddle_test:main")
        client.expect_no_event("stopped", within=2.0)
        check(True, "no stop without breakpoints")

        print("== pause ==")
        client.request("pause", {"threadId": 1})
        evaluate(client, "function saddle_test:main")
        stopped = client.wait_event("stopped")
        check(stopped["body"]["reason"] == "pause", "pause stop reason")
        frames = top_frame(client)
        check(frames[0]["line"] == 2, "paused on first command line", str(frames[0]))
        client.request("continue", {"threadId": 1})

        print("== macro & comment breakpoints ==")
        macro_fn = os.path.join(pack_dir, "data", "saddle_test", "function", "macro.mcfunction")
        bp_body = client.request("setBreakpoints", {
            "source": {"path": macro_fn},
            "breakpoints": [{"line": 1}],
        })["body"]["breakpoints"]
        check(bp_body[0]["verified"] is True and bp_body[0]["line"] == 2,
              "comment breakpoint shifts to next executable line", str(bp_body))

        client.request("setBreakpoints", {
            "source": {"path": macro_fn},
            "breakpoints": [{"line": 3}],
        })
        evaluate(client, "function saddle_test:macro {x: 5}")
        stopped = client.wait_event("stopped")
        check(stopped["body"]["reason"] == "breakpoint", "stopped on macro line")
        frames = top_frame(client)
        check(frames[0]["name"] == "saddle_test:macro" and frames[0]["line"] == 3,
              "macro frame at line 3", str(frames[0]))

        print("== live variables while suspended ==")
        scopes = client.request("scopes", {"frameId": frames[0]["id"]})["body"]["scopes"]
        scope_names = [s["name"] for s in scopes]
        check(scope_names == ["Executor", "Macro Arguments", "Command", "Scoreboard", "Storage"],
              "macro frame scopes", str(scope_names))
        by_name = {s["name"]: s["variablesReference"] for s in scopes}

        macro_vars = client.request("variables",
                                    {"variablesReference": by_name["Macro Arguments"]})["body"]["variables"]
        check(any(v["name"] == "$(x)" and v["value"] == "5" for v in macro_vars),
              "macro argument value in variables", str(macro_vars))

        hover = client.request("evaluate", {
            "expression": "$(x)", "context": "hover", "frameId": frames[0]["id"],
        })["body"]
        check(hover["result"] == "5", "macro argument on hover", str(hover))

        cmd_vars = client.request("variables",
                                  {"variablesReference": by_name["Command"]})["body"]["variables"]
        cmd_text = next((v["value"] for v in cmd_vars if v["name"] == "command"), "")
        check("$scoreboard players set macro_result" in cmd_text, "command scope shows line text", cmd_text)

        objectives = client.request("variables",
                                    {"variablesReference": by_name["Scoreboard"]})["body"]["variables"]
        dbg = next((o for o in objectives if o["name"] == "saddle_dbg"), None)
        check(dbg is not None and dbg["variablesReference"] > 0, "scoreboard objective node", str(objectives))
        scores = client.request("variables",
                                {"variablesReference": dbg["variablesReference"]})["body"]["variables"]
        target = next((s for s in scores if s["name"] == "target"), None)
        check(target is not None and target["value"] == "53", "score visible in variables", str(scores))

        set_resp = client.request("setVariable", {
            "variablesReference": dbg["variablesReference"], "name": "target", "value": "99",
        })["body"]
        check(set_resp["value"] == "99", "setVariable on score")
        result = evaluate(client, "scoreboard players get target saddle_dbg")
        check("99" in result, "score modified in game", result)

        evaluate(client, "data merge storage saddle_test:store {foo: {bar: 7}}")
        storages = client.request("variables",
                                  {"variablesReference": by_name["Storage"]})["body"]["variables"]
        store = next((s for s in storages if s["name"] == "saddle_test:store"), None)
        check(store is not None and store["variablesReference"] > 0, "storage node", str(storages))
        foo = next((v for v in client.request("variables",
                    {"variablesReference": store["variablesReference"]})["body"]["variables"]
                    if v["name"] == "foo"), None)
        check(foo is not None and foo["variablesReference"] > 0, "storage NBT compound node")
        bar = next((v for v in client.request("variables",
                    {"variablesReference": foo["variablesReference"]})["body"]["variables"]
                    if v["name"] == "bar"), None)
        check(bar is not None and bar["value"] == "7", "storage NBT leaf value", str(bar))
        client.request("setVariable", {
            "variablesReference": foo["variablesReference"], "name": "bar", "value": "21",
        })
        client.wait_event("invalidated", timeout=5)
        check(True, "setVariable triggers variables refresh")
        result = evaluate(client, "data get storage saddle_test:store foo.bar")
        check("21" in result, "storage NBT modified via setVariable", result)
        bar = next((v for v in client.request("variables",
                    {"variablesReference": foo["variablesReference"]})["body"]["variables"]
                    if v["name"] == "bar"), None)
        check(bar is not None and bar["value"] == "21", "re-read shows the new value", str(bar))

        print("== watch expressions & pins ==")
        watch = client.request("evaluate", {
            "expression": "storage saddle_test:store foo.bar", "context": "watch",
            "frameId": frames[0]["id"],
        })["body"]
        check(watch["result"] == "21", "storage watch expression", str(watch))
        watch = client.request("evaluate", {
            "expression": "score saddle_dbg target", "context": "watch",
            "frameId": frames[0]["id"],
        })["body"]
        check(watch["result"] == "99", "score watch expression", str(watch))
        watch = client.request("evaluate", {
            "expression": "storage saddle_test:store", "context": "watch",
            "frameId": frames[0]["id"],
        })["body"]
        check("entr" in watch["result"] and watch.get("variablesReference", 0) > 0,
              "storage watch is expandable with cheap preview", str(watch))

        client.request("saddle/pin", {"expression": "score saddle_dbg target"})
        client.request("saddle/pin", {"expression": "storage saddle_test:store foo"})
        scopes = client.request("scopes", {"frameId": frames[0]["id"]})["body"]["scopes"]
        watched_ref = next((s["variablesReference"] for s in scopes if s["name"] == "Watched"), None)
        check(watched_ref is not None, "Watched scope appears after pinning",
              str([s["name"] for s in scopes]))
        pinned = client.request("variables", {"variablesReference": watched_ref})["body"]["variables"]
        by_pin = {v["name"]: v for v in pinned}
        check(by_pin.get("score saddle_dbg target", {}).get("value") == "99",
              "pinned score value", str(pinned))
        storage_pin = by_pin.get("storage saddle_test:store foo")
        check(storage_pin is not None and storage_pin["variablesReference"] > 0,
              "pinned storage node expandable", str(pinned))
        inner = client.request("variables",
                               {"variablesReference": storage_pin["variablesReference"]})["body"]["variables"]
        check(any(v["name"] == "bar" and v["value"] == "21" for v in inner),
              "pinned storage expands to live values", str(inner))
        client.request("saddle/unpin", {"expression": "score saddle_dbg target"})
        pins_left = client.request("saddle/pins")["body"]["pins"]
        check(pins_left == ["storage saddle_test:store foo"], "unpin removes expression", str(pins_left))
        client.request("saddle/unpin", {"expression": "storage saddle_test:store foo"})

        client.request("continue", {"threadId": 1})
        time.sleep(0.5)
        result = evaluate(client, "scoreboard players get macro_result saddle_dbg")
        check("5" in result, "macro arguments applied after continue", result)
        # The setVariable edit made while suspended must persist after resume,
        # observed through the normal (non-isolated) command path.
        result = evaluate(client, "data get storage saddle_test:store foo.bar")
        check("21" in result, "storage edit persists after resume", result)
        client.request("setBreakpoints", {"source": {"path": macro_fn}, "breakpoints": []})

        print("== entity & block data requests ==")
        evaluate(client, "forceload add 0 0")
        evaluate(client, "kill @e[type=marker]")
        # The forceloaded chunk loads asynchronously; retry until the summon lands.
        marker = []
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline and not marker:
            evaluate(client, "summon minecraft:marker 8 10 8")
            time.sleep(0.5)
            marker = client.request("minecraft/listEntities",
                                    {"selector": "@e[type=marker,limit=1]"})["body"]["entities"]
        check(len(marker) == 1, "marker summoned", str(marker))
        uuid = marker[0]["uuid"]

        pos = client.request("minecraft/getData",
                             {"type": "entity", "target": uuid, "path": "Pos"})["body"]["value"]
        check("8" in pos, "entity NBT read", pos)
        client.request("minecraft/setData",
                       {"type": "entity", "target": uuid, "path": "data.k", "value": "42"})
        value = client.request("minecraft/getData",
                               {"type": "entity", "target": uuid, "path": "data.k"})["body"]["value"]
        check(value == "42", "entity NBT write", value)

        evaluate(client, "setblock 9 10 8 minecraft:barrel")
        block = client.request("minecraft/getBlock", {"pos": "9 10 8"})["body"]
        check("barrel" in block["state"], "block state read", str(block))
        client.request("minecraft/setData", {
            "type": "block", "target": "9 10 8", "path": "CustomName", "value": '"crate"',
        })
        value = client.request("minecraft/getData",
                               {"type": "block", "target": "9 10 8", "path": "CustomName"})["body"]["value"]
        check("crate" in value, "block entity NBT write", value)

        print("== custom introspection requests ==")
        scoreboard = client.request("minecraft/getScoreboard")["body"]
        objective = next((o for o in scoreboard["objectives"] if o["name"] == "saddle_dbg"), None)
        check(objective is not None, "scoreboard dump contains saddle_dbg")
        score = next((s for s in objective["scores"] if s["holder"] == "target"), None)
        check(score is not None and score["value"] == 99, "scoreboard dump has target=99", str(objective))

        entities = client.request("minecraft/listEntities", {"selector": "@e[limit=5]"})["body"]
        check("entities" in entities, "listEntities request")
        storage = client.request("minecraft/getStorage")["body"]
        check("keys" in storage, "getStorage keys request")

        print("== selector & coordinate resolution ==")
        sel_fn = os.path.join(pack_dir, "data", "saddle_test", "function", "sel.mcfunction")
        client.request("setBreakpoints", {"source": {"path": sel_fn}, "breakpoints": [{"line": 2}]})
        evaluate(client, "function saddle_test:sel")
        client.wait_event("stopped")
        frames = top_frame(client)
        scopes = client.request("scopes", {"frameId": frames[0]["id"]})["body"]["scopes"]
        command_ref = next(s["variablesReference"] for s in scopes if s["name"] == "Command")
        cmd_vars = client.request("variables", {"variablesReference": command_ref})["body"]["variables"]
        selector = next((v for v in cmd_vars if v["name"] == "@e[type=marker,limit=1]"), None)
        check(selector is not None and "1 entit" in selector["value"],
              "selector resolves in Command scope", str(cmd_vars))
        matched = client.request("variables",
                                 {"variablesReference": selector["variablesReference"]})["body"]["variables"]
        check(len(matched) == 1 and "marker" in matched[0]["value"], "selector expands to entities", str(matched))

        coords = next((v for v in cmd_vars if v["name"] == "~ ~2 ~"), None)
        check(coords is not None and "minecraft:" in coords["value"],
              "coordinate triple resolves to a block", str(cmd_vars))

        hover = client.request("evaluate", {
            "expression": "@e[type=marker,limit=1]", "context": "hover", "frameId": frames[0]["id"],
        })["body"]
        check("1 entit" in hover["result"] and hover.get("variablesReference", 0) > 0,
              "selector hover with expandable result", str(hover))
        hover = client.request("evaluate", {
            "expression": "~ ~2 ~", "context": "hover", "frameId": frames[0]["id"],
        })["body"]
        check("minecraft:" in hover["result"], "coordinate hover shows block", str(hover))

        client.request("continue", {"threadId": 1})
        client.request("setBreakpoints", {"source": {"path": sel_fn}, "breakpoints": []})

        print("== debug console completions ==")
        targets = client.request("completions", {"text": "scoreb", "column": 7})["body"]["targets"]
        check(any(t["label"] == "scoreboard" for t in targets), "command name completion", str(targets)[:200])
        targets = client.request("completions", {"text": "scoreboard ", "column": 12})["body"]["targets"]
        labels = {t["label"] for t in targets}
        check("objectives" in labels and "players" in labels, "argument completion", str(labels))

        print("== chat output bridge ==")
        evaluate(client, "say chat-bridge-probe")
        deadline = time.monotonic() + 10
        seen = None
        while time.monotonic() < deadline and seen is None:
            try:
                event = client.events.get(timeout=deadline - time.monotonic())
            except queue.Empty:
                break
            if event.get("event") == "output" and "chat-bridge-probe" in event["body"].get("output", ""):
                seen = event["body"]
        check(seen is not None and seen.get("category") == "stdout",
              "say broadcast mirrored to debug console", str(seen))

        print("== entity executor NBT scope ==")
        client.request("setBreakpoints", {"source": {"path": main_fn}, "breakpoints": [{"line": 4}]})
        evaluate(client, "execute as @e[type=marker,limit=1] run function saddle_test:main")
        client.wait_event("stopped")
        frames = top_frame(client)
        scopes = client.request("scopes", {"frameId": frames[0]["id"]})["body"]["scopes"]
        executor_ref = next(s["variablesReference"] for s in scopes if s["name"] == "Executor")
        executor_vars = client.request("variables",
                                       {"variablesReference": executor_ref})["body"]["variables"]
        names = {v["name"] for v in executor_vars}
        check("uuid" in names, "executor identifies entity", str(names))
        nbt = next((v for v in executor_vars if v["name"] == "nbt"), None)
        check(nbt is not None and nbt["variablesReference"] > 0, "executor has live NBT node")
        nbt_vars = client.request("variables",
                                  {"variablesReference": nbt["variablesReference"]})["body"]["variables"]
        check(any(v["name"] == "Pos" for v in nbt_vars), "entity NBT tree expands", str(nbt_vars)[:200])
        client.request("continue", {"threadId": 1})
        client.request("setBreakpoints", {"source": {"path": main_fn}, "breakpoints": []})

        print("== disconnect ==")
        client.request("disconnect", {})
        print(f"\nPASS: all {passed} checks succeeded")
        return 0
    finally:
        client.close()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except DapError as e:
        print(f"\n{e}", file=sys.stderr)
        sys.exit(1)

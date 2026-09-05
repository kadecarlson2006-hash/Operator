#!/usr/bin/env python3
"""End-to-end smoke test for the Milestone 5 memory API against a running backend.

Usage: python3 backend/scripts/memory_api_smoke.py http://localhost:8080
Requires OPERATOR_DEMO_SEED_ENABLED=true on the backend for the demo-seed step.
Only the standard library is used so it runs on any CI runner or workstation.
"""
import json
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else "http://localhost:8080"


def call(method, path, body=None, expect=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            status = resp.status
            payload = resp.read()
    except urllib.error.HTTPError as e:
        status = e.code
        payload = e.read()
    parsed = json.loads(payload) if payload else None
    if expect is not None:
        assert status == expect, f"{method} {path}: expected {expect}, got {status}: {payload[:300]!r}"
    print(f"{method:6} {path:45} -> {status}")
    return parsed


def main():
    person = call("POST", "/people", {"name": "Smoke Person", "role": "tester"}, expect=201)
    project = call("POST", "/projects", {"name": "Smoke Project"}, expect=201)

    m = call("POST", "/memory", {
        "memoryType": "WORK_FACT", "content": "Smoke: the widget line runs on Tuesdays", "importance": 0.8,
        "privacyScope": "WORK", "personId": person["id"], "projectId": project["id"], "metadata": {"k": "v"},
    }, expect=201)
    mid = m["id"]
    assert m["personId"] == person["id"] and m["projectId"] == project["id"] and m["metadata"] == {"k": "v"}, m

    got = call("GET", f"/memory/{mid}", expect=200)
    assert got["content"] == "Smoke: the widget line runs on Tuesdays", got

    dup = call("POST", "/memory", {"memoryType": "WORK_FACT", "content": "smoke:  the WIDGET line runs on tuesdays"}, expect=409)
    assert dup["existingId"] == mid, dup

    hits = call("GET", "/memory/search?text=widget%20line&type=WORK_FACT&scope=WORK", expect=200)
    assert [h["id"] for h in hits] == [mid], hits
    assert call("GET", f"/memory/search?personId={person['id']}", expect=200)[0]["id"] == mid
    assert call("GET", "/memory/search?text=nonexistent-zzz", expect=200) == []

    patched = call("PATCH", f"/memory/{mid}", {"importance": 0.95, "metadata": {"k": "v2"}}, expect=200)
    assert abs(patched["importance"] - 0.95) < 1e-6 and patched["metadata"]["k"] == "v2", patched

    other = call("POST", "/memory", {"memoryType": "EPISODIC_EVENT", "content": "Smoke: truck stuck at the dock"}, expect=201)
    call("PUT", f"/memory/{mid}/embedding", {"model": "fake-3d", "vector": [1, 0, 0]}, expect=200)
    call("PUT", f"/memory/{other['id']}/embedding", {"model": "fake-3d", "vector": [0, 1, 0]}, expect=200)
    assert call("GET", f"/memory/{mid}", expect=200)["hasEmbedding"] is True
    sim = call("POST", "/memory/search/similar", {"vector": [0.1, 0.9, 0], "limit": 5, "model": "fake-3d"}, expect=200)
    assert [h["id"] for h in sim][:2] == [other["id"], mid], sim
    assert sim[0]["distance"] < sim[1]["distance"], sim
    assert call("POST", "/memory/search/similar", {"vector": [1, 0], "limit": 5}, expect=200) == [], "dimension mismatch must return nothing"

    touched = call("POST", f"/memory/{mid}/touch", expect=200)
    assert touched["lastUsedAt"], touched

    disabled = call("PATCH", f"/memory/{mid}", {"isActive": False}, expect=200)
    assert disabled["isActive"] is False
    assert call("GET", "/memory/search?text=widget%20line", expect=200) == []
    assert len(call("GET", "/memory/search?text=widget%20line&includeInactive=true", expect=200)) == 1
    call("PATCH", f"/memory/{mid}", {"isActive": True}, expect=200)

    marked = call("POST", f"/memory/{other['id']}/mark-incorrect", {"reason": "never happened"}, expect=200)
    assert marked["confidence"] == 0 and marked["isActive"] is False, marked

    events = call("GET", f"/memory/{mid}/events", expect=200)
    kinds = [e["eventType"] for e in events]
    for k in ("CREATED", "UPDATED", "EMBEDDED", "USED", "DISABLED", "ENABLED"):
        assert k in kinds, kinds

    call("DELETE", f"/memory/{mid}", expect=204)
    call("GET", f"/memory/{mid}", expect=404)
    assert "DELETED" in [e["eventType"] for e in call("GET", f"/memory/{mid}/events", expect=200)]

    assert call("GET", "/memory/not-a-uuid", expect=400)["error"]
    assert call("POST", "/memory", {"memoryType": "BOGUS", "content": "x"}, expect=400)["error"]
    assert call("POST", "/memory", {"memoryType": "GOAL", "content": "x", "personId": "00000000-0000-0000-0000-00000000dead"}, expect=404)["error"]

    seed = call("POST", "/memory/demo-seed", expect=200)
    assert seed["created"] == 12, seed
    again = call("POST", "/memory/demo-seed", expect=200)
    assert again["created"] == 0 and again["skipped"] == 12, again
    west = call("GET", "/memory/search?text=handles%20the%20west", expect=200)
    assert len(west) == 1 and west[0]["personId"], west

    print("memory API smoke test: OK")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Minimal dependency-free MCP stdio server for the Redmi Termux agent."""
from __future__ import annotations
import json
import sys
import agent

SERVER_INFO = {"name": "jarvis-redmi-termux-agent", "version": "0.1.0"}
PROTOCOL = "2024-11-05"


def send(message):
    sys.stdout.write(json.dumps(message, ensure_ascii=False, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def tool_list():
    tools = []
    for name, (description, _) in sorted(agent.SKILLS.items()):
        tools.append({
            "name": name,
            "description": description,
            "inputSchema": {"type": "object", "additionalProperties": True},
        })
    tools.append({
        "name": "jarvis_legacy_catalog",
        "description": "Discover the 134 capability names migrated from the original Jarvis Android project.",
        "inputSchema": {"type": "object", "properties": {}},
    })
    return tools


def call(name, args):
    if name == "jarvis_legacy_catalog":
        try:
            return json.loads(agent.CATALOG.read_text())
        except Exception as exc:
            return {"success": False, "error": str(exc)}
    return agent.execute(name, args)


def main():
    for line in sys.stdin:
        if not line.strip():
            continue
        request_id = None
        try:
            req = json.loads(line)
            request_id = req.get("id")
            method = req.get("method")
            params = req.get("params") or {}
            if method == "initialize":
                send({"jsonrpc":"2.0","id":request_id,"result":{
                    "protocolVersion": PROTOCOL,
                    "capabilities":{"tools":{}},
                    "serverInfo": SERVER_INFO,
                }})
            elif method == "notifications/initialized":
                continue
            elif method == "ping":
                send({"jsonrpc":"2.0","id":request_id,"result":{}})
            elif method == "tools/list":
                send({"jsonrpc":"2.0","id":request_id,"result":{"tools":tool_list()}})
            elif method == "tools/call":
                name = params.get("name", "")
                args = params.get("arguments") or {}
                result = call(name, args)
                send({"jsonrpc":"2.0","id":request_id,"result":{
                    "content":[{"type":"text","text":json.dumps(result, ensure_ascii=False, indent=2)}],
                    "isError": not result.get("success", True),
                }})
            elif request_id is not None:
                send({"jsonrpc":"2.0","id":request_id,"error":{"code":-32601,"message":f"Method not found: {method}"}})
        except Exception as exc:
            if request_id is not None:
                send({"jsonrpc":"2.0","id":request_id,"error":{"code":-32603,"message":str(exc)}})


if __name__ == "__main__":
    main()

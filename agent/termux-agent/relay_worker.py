#!/usr/bin/env python3
"""Small authenticated HTTP relay worker for the Termux JARVIS agent."""
from __future__ import annotations
import json,os,time,urllib.error,urllib.request
from pathlib import Path
import agent,relay_protocol
POLL_SECONDS=max(1,int(os.environ.get("JARVIS_RELAY_POLL_SECONDS","5")))
TIMEOUT_SECONDS=max(1,int(os.environ.get("JARVIS_RELAY_TIMEOUT_SECONDS","20")))
RELAY_URL=os.environ.get("JARVIS_RELAY_URL","").strip()
CLIENT_ID=os.environ.get("JARVIS_RELAY_CLIENT_ID","termux-phone").strip()
JOURNAL=Path(os.environ.get("JARVIS_RELAY_JOURNAL",str(agent.ROOT/"relay-journal.jsonl")))
def _secret()->str: return agent.token()
def _post(payload:dict)->dict:
    body=json.dumps(payload,separators=(",",":"),ensure_ascii=False).encode()
    request=urllib.request.Request(RELAY_URL,data=body,method="POST",headers={"Content-Type":"application/json","User-Agent":"jarvis-relay/1"})
    with urllib.request.urlopen(request,timeout=TIMEOUT_SECONDS) as response: return json.loads(response.read().decode("utf-8"))
def _journal(event:dict)->None:
    JOURNAL.parent.mkdir(parents=True,exist_ok=True)
    with JOURNAL.open("a",encoding="utf-8") as handle: handle.write(json.dumps(event,separators=(",",":"),ensure_ascii=False)+"
")
def process(envelope:dict)->dict:
    secret=_secret()
    if not relay_protocol.verify(envelope,secret): return {"success":False,"error":{"code":"INVALID_RELAY_SIGNATURE","message":"request authentication failed"}}
    request_id=envelope["id"]; method=envelope.get("method","")
    if method=="poll": return {"success":True,"kind":"ready","clientId":CLIENT_ID}
    if method!="execute": return {"success":False,"error":{"code":"UNKNOWN_RELAY_METHOD","message":method}}
    params=envelope.get("params") or {}
    if not isinstance(params,dict) or not isinstance(params.get("name"),str): return {"success":False,"error":{"code":"INVALID_EXECUTE_PARAMS","message":"name is required"}}
    name=params["name"]; arguments=params.get("arguments") or {}
    if not isinstance(arguments,dict): return {"success":False,"error":{"code":"INVALID_EXECUTE_PARAMS","message":"arguments must be an object"}}
    started=time.time(); result=agent.execute(name,arguments)
    _journal({"id":request_id,"name":name,"success":bool(result.get("success",False)),"durationMs":round((time.time()-started)*1000)})
    return result
def loop()->None:
    if not RELAY_URL: raise SystemExit("JARVIS_RELAY_URL is required; relay worker is disabled by default")
    while True:
        try:
            request=relay_protocol.make_request("poll",{},_secret(),CLIENT_ID); response=_post(request)
            if not relay_protocol.verify_response(response,_secret()): raise RuntimeError("relay response authentication failed")
            command=response.get("result",{})
            if isinstance(command,dict) and command.get("kind")=="command":
                envelope=command.get("request")
                if isinstance(envelope,dict): _post(relay_protocol.make_response(envelope,process(envelope),_secret()))
        except (urllib.error.URLError,TimeoutError,OSError,ValueError,RuntimeError) as exc: _journal({"event":"relay-error","message":str(exc)})
        time.sleep(POLL_SECONDS)
if __name__=="__main__": loop()


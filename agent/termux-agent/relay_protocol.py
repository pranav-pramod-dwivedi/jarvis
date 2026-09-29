"""Authenticated JSON envelopes for phone-initiated Jarvis relay transport."""
from __future__ import annotations
import hashlib,hmac,json,secrets,time
from typing import Any
PROTOCOL="jarvis-relay-v1"
MAX_SKEW_SECONDS=120

def canonical(value: Any)->bytes:
    return json.dumps(value,sort_keys=True,separators=(",",":"),ensure_ascii=False).encode()

def sign(envelope:dict[str,Any],secret:str)->str:
    body={k:v for k,v in envelope.items() if k!="signature"}
    return hmac.new(secret.encode(),canonical(body),hashlib.sha256).hexdigest()

def make_request(method:str,params:dict[str,Any],secret:str,client_id:str)->dict[str,Any]:
    envelope={"protocol":PROTOCOL,"kind":"request","id":secrets.token_hex(16),"clientId":client_id,
              "timestamp":int(time.time()),"method":method,"params":params}
    envelope["signature"]=sign(envelope,secret)
    return envelope

def verify(envelope:dict[str,Any],secret:str,now:int|None=None,max_skew:int=MAX_SKEW_SECONDS)->bool:
    now=int(time.time()) if now is None else now
    if envelope.get("protocol")!=PROTOCOL or envelope.get("kind")!="request": return False
    timestamp=envelope.get("timestamp")
    if not isinstance(timestamp,int) or abs(now-timestamp)>max_skew: return False
    supplied=str(envelope.get("signature",""))
    return bool(supplied) and hmac.compare_digest(supplied,sign(envelope,secret))

#!/usr/bin/env python3
import sys,time
sys.path.insert(0,"agent/termux-agent")
import relay_protocol

secret="test-secret-with-sufficient-entropy"
req=relay_protocol.make_request("poll",{},secret,"self-test")
assert relay_protocol.verify(req,secret)
assert not relay_protocol.verify(req,"wrong-secret")
tampered=dict(req); tampered["method"]="execute"
assert not relay_protocol.verify(tampered,secret)
stale=dict(req); stale["timestamp"]=int(time.time())-relay_protocol.MAX_SKEW_SECONDS-1
stale["signature"]=relay_protocol.sign(stale,secret)
assert not relay_protocol.verify(stale,secret)
print("relay protocol: ok")

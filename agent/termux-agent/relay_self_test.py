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

response=relay_protocol.make_response(req,{"success":True,"echo":"ok"},secret)
assert relay_protocol.verify_response(response,secret)
bad_response=dict(response); bad_response["result"]={"success":False}
assert not relay_protocol.verify_response(bad_response,secret)
assert not relay_protocol.verify_response({"protocol":"wrong","kind":"response","id":"x","timestamp":int(time.time()),"result":{},"signature":"x"},secret)
print("relay response protocol: ok")

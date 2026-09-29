#!/usr/bin/env python3
import sys,time
sys.path.insert(0,'agent/termux-agent')
import agent,relay_protocol,relay_worker
secret='worker-self-test-secret'
agent.token=lambda: secret
poll=relay_protocol.make_request('poll',{},secret,'test-client')
ready=relay_worker.process(poll)
assert ready['success'] and ready['kind']=='ready'
execute=relay_protocol.make_request('execute',{'name':'agent.info','arguments':{}},secret,'test-client')
result=relay_worker.process(execute)
assert result['success'] and result['deviceTarget']=='Redmi Note 8 Pro'
replay=relay_worker.process(execute)
assert not replay['success'] and replay['error']['code']=='REPLAYED_REQUEST'
invalid=relay_protocol.make_request('execute',{'name':'device.vibrate','arguments':{'durationMs':0}},secret,'test-client')
invalid_result=relay_worker.process(invalid)
assert not invalid_result['success'] and '>= 1' in invalid_result['error']
bad=dict(execute); bad['signature']=relay_protocol.sign(bad,'wrong')
rejected=relay_worker.process(bad)
assert not rejected['success'] and rejected['error']['code']=='INVALID_RELAY_SIGNATURE'
stale=dict(execute); stale['timestamp']=int(time.time())-relay_protocol.MAX_SKEW_SECONDS-1; stale['signature']=relay_protocol.sign(stale,secret)
rejected=relay_worker.process(stale)
assert not rejected['success']
print('relay worker: ok')

#!/usr/bin/env python3
"""Dependency-free MCP stdio server for the rooted Redmi JARVIS agent."""
from __future__ import annotations
import json,sys
import agent
import legacy_dispatch

SERVER_INFO={"name":"jarvis-redmi-termux-agent","version":"0.2.0"}
PROTOCOL="2024-11-05"

def schema(name):
    common={"type":"object","additionalProperties":True}
    exact={
      'device.battery':{},'device.location':{},'device.clipboard_get':{},'device.wifi':{},
      'device.tts_engines':{},'device.telephony_info':{},'agent.info':{},'device.system_info':{},'device.root_status':{},'diagnostics.snapshot':{},'diagnostics.health_check':{},
      'device.notification':{'properties':{'title':{'type':'string'},'content':{'type':'string'}},'required':['content']},
      'device.toast':{'properties':{'text':{'type':'string'}},'required':['text']},
      'device.clipboard_set':{'properties':{'text':{'type':'string'}},'required':['text']},
      'device.vibrate':{'properties':{'durationMs':{'type':'integer','minimum':1,'maximum':10000}}},
      'device.torch':{'properties':{'state':{'type':'boolean'}}},
      'device.volume':{'properties':{'stream':{'type':'string'},'level':{'type':'integer','minimum':0}}},
      'device.brightness':{'properties':{'level':{'type':'integer','minimum':0,'maximum':255}}},
      'device.call':{'properties':{'number':{'type':'string'}},'required':['number']},
      'device.sms':{'properties':{'number':{'type':'string'},'text':{'type':'string'}},'required':['number','text']},
      'device.screenshot':{'properties':{'path':{'type':'string'}}},
      'device.processes':{'properties':{'limit':{'type':'integer','minimum':1,'maximum':200}}},
      'device.packages':{'properties':{'query':{'type':'string'},'limit':{'type':'integer','minimum':1,'maximum':1000}}},
      'android.open_app':{'properties':{'package':{'type':'string'}},'required':['package']},
      'android.open_settings':{'properties':{'action':{'type':'string'}}},
      'android.open_url':{'properties':{'url':{'type':'string','format':'uri'}},'required':['url']},
      'files.read':{'properties':{'path':{'type':'string'}},'required':['path']},
      'shell.exec':{'properties':{'command':{'type':'string'}},'required':['command']},
    }
    if name in exact: return {'type':'object',**exact[name]}
    return common

def tool_list():
    out=[]
    for name,(desc,_) in sorted(agent.SKILLS.items()):
        out.append({'name':name,'description':desc,'inputSchema':schema(name)})
    for name,cap in sorted(legacy_dispatch.CAPS.items()):
        out.append({'name':'jarvis.'+name,'description':f"Legacy JARVIS capability [{cap['status']}]: {cap['reason']}",'inputSchema':common_legacy_schema(name)})
    out.append({'name':'jarvis.capabilities','description':'Return the complete 134-tool JARVIS capability matrix with execution status.','inputSchema':{'type':'object','properties':{}}})
    return out

def common_legacy_schema(name):
    return {'type':'object','additionalProperties':True,'properties':{'path':{'type':'string'},'source':{'type':'string'},'destination':{'type':'string'},'text':{'type':'string'},'x':{'type':'integer'},'y':{'type':'integer'},'key':{'type':'string'},'package':{'type':'string'},'url':{'type':'string'}}}

def call(name,args):
    if name=='jarvis.capabilities': return {'success':True,**legacy_dispatch.MATRIX}
    if name.startswith('jarvis.'):
        return legacy_dispatch.execute(name[7:],args)
    return agent.execute(name,args)

def send(x):
    sys.stdout.write(json.dumps(x,separators=(',',':'),ensure_ascii=False)+'\n');sys.stdout.flush()

def main():
    for line in sys.stdin:
        if not line.strip(): continue
        rid=None
        try:
            req=json.loads(line);rid=req.get('id');method=req.get('method');params=req.get('params') or {}
            if method=='initialize': send({'jsonrpc':'2.0','id':rid,'result':{'protocolVersion':PROTOCOL,'capabilities':{'tools':{}},'serverInfo':SERVER_INFO}})
            elif method=='notifications/initialized': pass
            elif method=='ping': send({'jsonrpc':'2.0','id':rid,'result':{}})
            elif method=='tools/list': send({'jsonrpc':'2.0','id':rid,'result':{'tools':tool_list()}})
            elif method=='tools/call':
                result=call(params.get('name',''),params.get('arguments') or {})
                send({'jsonrpc':'2.0','id':rid,'result':{'content':[{'type':'text','text':json.dumps(result,ensure_ascii=False)}],'isError':not result.get('success',True)}})
            elif rid is not None: send({'jsonrpc':'2.0','id':rid,'error':{'code':-32601,'message':f'Method not found: {method}'}})
        except Exception as e:
            if rid is not None: send({'jsonrpc':'2.0','id':rid,'error':{'code':-32603,'message':str(e)}})

if __name__=='__main__': main()

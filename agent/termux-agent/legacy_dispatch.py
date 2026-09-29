#!/usr/bin/env python3
"""Compatibility dispatcher for the original JARVIS 134-tool surface.

It never fabricates support: every legacy tool reports whether it is native,
mapped, root-shell, root-ui, Android-app, network, or still needs an adapter.
"""
from __future__ import annotations
import json
from pathlib import Path
import agent

ROOT=Path(__file__).resolve().parent
MATRIX=json.loads((ROOT/'capability_matrix.json').read_text())
CAPS={x['name']:x for x in MATRIX['capabilities']}
ALIASES={x['name']:x['target'] for x in MATRIX['capabilities'] if x.get('target')}

INTENT_BY_PREFIX={
    'calendar_open_app':'android.settings.CALENDAR_SETTINGS',
    'clock_open_app':'android.settings.ALARM_SETTINGS',
    'reminder_open_app':'android.settings.TASKS_SETTINGS',
}

def _result(name, args):
    cap=CAPS.get(name)
    if not cap:
        return {'success':False,'error':{'code':'UNKNOWN_LEGACY_TOOL','message':name}}
    target=cap.get('target')
    if target and target in agent.SKILLS:
        return agent.execute(target,args)
    status=cap['status']
    if status=='native':
        return {'success':False,'error':{'code':'NATIVE_REGISTRATION_MISSING','message':name}}
    if status=='root-ui':
        return _ui(name,args)
    if status=='root-shell':
        return _shell(name,args)
    if status=='android-app':
        action=INTENT_BY_PREFIX.get(name)
        if action:
            return agent.run(['am','start','-a',action])
    return {'success':False,'error':{'code':'CAPABILITY_NOT_YET_ADAPTER','message':name,'status':status,'reason':cap['reason']}}

def _ui(name,args):
    if name in {'click','virtual_touch'}:
        if 'x' in args and 'y' in args:
            return agent.run(['su','-c',f"input tap {int(args['x'])} {int(args['y'])}"])
        return {'success':False,'error':{'code':'ARGUMENT_REQUIRED','message':'x and y are required'}}
    if name in {'scroll','virtual_scroll'}:
        x1=int(args.get('x1',int(args.get('x',500)))); y1=int(args.get('y1',700)); x2=int(args.get('x2',x1)); y2=int(args.get('y2',100)); dur=int(args.get('durationMs',400))
        return agent.run(['su','-c',f'input swipe {x1} {y1} {x2} {y2} {dur}'])
    if name in {'type_text','virtual_type'}:
        text=str(args.get('text','')).replace('%','%25').replace(' ','%s')
        return agent.run(['su','-c',f'input text {text}'])
    if name=='press_global_key':
        keys={'back':4,'home':3,'recents':187,'notifications':83,'quick_settings':84,'lock_screen':26}
        key=str(args.get('key','back')).lower()
        if key not in keys: return {'success':False,'error':{'code':'UNKNOWN_KEY','message':key}}
        return agent.run(['su','-c',f'input keyevent {keys[key]}'])
    if name in {'screen_info','read_screen_text'}:
        out='/data/local/tmp/jarvis-window.xml'
        r=agent.run(['su','-c',f'uiautomator dump {out} >/dev/null 2>&1 && cat {out}'])
        if r['success']:
            r['stdout']=r['stdout'][:20000]
        return r
    return {'success':False,'error':{'code':'ROOT_UI_ADAPTER_MISSING','message':name}}

def _shell(name,args):
    if name=='delete_file':
        return agent.run(['su','-c',f"rm -rf -- {json.dumps(str(args['path']))}"])
    if name=='rename_file':
        return agent.run(['su','-c',f"mv -- {json.dumps(str(args['source']))} {json.dumps(str(args['destination']))}"])
    if name=='copy_file':
        return agent.run(['su','-c',f"cp -a -- {json.dumps(str(args['source']))} {json.dumps(str(args['destination']))}"])
    if name=='create_folder':
        return agent.run(['su','-c',f"mkdir -p -- {json.dumps(str(args['path']))}"])
    if name=='write_file':
        return agent.run(['su','-c',f"python3 -c 'from pathlib import Path; Path({str(args['path'])!r}).write_text({str(args.get('content',''))!r})'"])
    if name in {'search_files','find_downloads'}:
        base=str(args.get('path','/sdcard/Download'))
        query=str(args.get('query','*'))
        return agent.run(['su','-c',f"find {json.dumps(base)} -iname {json.dumps(query)} -print | head -200"])
    if name=='file_storage_stats':
        return agent.run(['df','-h','/data','/sdcard'])
    if name=='diagnostic_ping':
        return agent.run(['ping','-c','1','-W','2',str(args.get('host','1.1.1.1'))])
    if name=='jarvis_environment':
        return agent.run(['sh','-lc','getprop ro.product.model; getprop ro.build.version.release; id; df -h /data /sdcard; uname -a'])
    return {'success':False,'error':{'code':'ROOT_SHELL_ADAPTER_MISSING','message':name}}

def execute(name,args):
    return _result(name,args or {})

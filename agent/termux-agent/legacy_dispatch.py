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

def _safe_target(value):
    p=Path(str(value)).expanduser().resolve()
    protected={Path('/'),Path('/data'),Path('/system'),Path('/vendor'),Path('/product'),Path('/sdcard'),Path('/storage')}
    if p in protected or len(p.parts)<4:
        raise ValueError('protected or insufficiently specific path')
    return str(p)

def _shell(name,args):
    try:
        if name=='close_app':
            return agent.execute('apps.force_stop',{'package':args['package']})
        if name=='delete_file':
            path=_safe_target(args['path'])
            return agent.execute('files.delete',{'path':path,'recursive':bool(args.get('recursive',False))})
        if name=='rename_file':
            src=_safe_target(args['source']); dst=_safe_target(args['destination'])
            return agent.run(['su','-c',f"mv -- {json.dumps(src)} {json.dumps(dst)}"])
        if name=='copy_file':
            src=_safe_target(args['source']); dst=_safe_target(args['destination'])
            return agent.run(['su','-c',f"cp -a -- {json.dumps(src)} {json.dumps(dst)}"])
        if name=='create_folder':
            path=_safe_target(args['path'])
            return agent.run(['su','-c',f"mkdir -p -- {json.dumps(path)}"])
        if name=='write_file':
            return agent.execute('files.write',{'path':args['path'],'content':args.get('content',''),'allowSystemPath':True})
        if name in {'search_files','find_downloads'}:
            base=str(args.get('path','/sdcard/Download')); query=str(args.get('query','*'))
            return agent.execute('files.search',{'path':base,'pattern':query,'limit':200})
        if name=='file_storage_stats':
            return agent.execute('device.system_info',{})
        if name=='diagnostic_ping':
            return agent.execute('network.ping',{'host':args.get('host','1.1.1.1')})
        if name=='jarvis_environment':
            return agent.execute('device.system_info',{})
        if name=='call_history':
            return agent.run(['su','-c','content query --uri content://call_log/calls --projection number,date,type,duration --sort "date DESC" 2>/dev/null | head -100'])
        if name=='clock_alarm_set':
            argv=['am','start','-a','android.intent.action.SET_ALARM']
            if 'hour' in args: argv += ['--ei','android.intent.extra.alarm.HOUR',str(int(args['hour']))]
            if 'minute' in args: argv += ['--ei','android.intent.extra.alarm.MINUTES',str(int(args['minute']))]
            if args.get('message'): argv += ['--es','android.intent.extra.alarm.MESSAGE',str(args['message'])]
            return agent.run(argv)
        if name=='clock_timer_start':
            argv=['am','start','-a','android.intent.action.SET_TIMER','--ei','android.intent.extra.alarm.LENGTH',str(int(args['seconds']))]
            return agent.run(argv)
        if name=='calendar_event_create':
            argv=['am','start','-a','android.intent.action.INSERT','-t','vnd.android.cursor.item/event','-d','content://com.android.calendar/events']
            if args.get('title'): argv += ['--es','title',str(args['title'])]
            if args.get('description'): argv += ['--es','description',str(args['description'])]
            if args.get('location'): argv += ['--es','eventLocation',str(args['location'])]
            return agent.run(argv)
        if name=='calendar_next_meeting':
            return agent.run(['su','-c','content query --uri content://com.android.calendar/events --projection title,dtstart,dtend,eventLocation --where "dtstart>"'"'"'$(date +%s)000'"'"' --sort "dtstart ASC" 2>/dev/null | head -20'])
        if name in {'list_add_item','list_view'}:
            if name=='list_add_item':
                return agent.execute('notes.create',{'title':str(args.get('list','default')),'body':str(args.get('item',args.get('text','')))})
            return agent.execute('notes.list',{'query':str(args.get('list',''))})
    except Exception as exc:
        return {'success':False,'error':{'code':'ROOT_SHELL_VALIDATION','message':str(exc)}}
    return {'success':False,'error':{'code':'ROOT_SHELL_ADAPTER_MISSING','message':name}}

def execute(name,args):
    return _result(name,args or {})

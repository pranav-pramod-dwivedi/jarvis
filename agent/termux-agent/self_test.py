#!/usr/bin/env python3
import sys,json
sys.path.insert(0,"agent/termux-agent")
import agent,legacy_dispatch
assert len(legacy_dispatch.CAPS)==134
for name,cap in legacy_dispatch.CAPS.items():
    assert cap['status'] in {'native','mapped','root-shell','root-ui','network','android-app','jarvis-browser','adapter-needed'}
    if cap.get('target'): assert cap['target'] in agent.SKILLS,(name,cap['target'])
assert len(agent.SKILLS)>=170
assert hasattr(agent, 'extended_skills') or 'extended_skills' in sys.modules
print('agent skills:',len(agent.SKILLS))
print('legacy capabilities:',len(legacy_dispatch.CAPS))
print('mapped targets valid: yes')
print('matrix integrity: yes')
import relay_worker_self_test

"""Execute the real claim Lua with Lupa and an in-memory Redis command stub.

Install optional test runtime: python -m pip install lupa==2.6
This verifies script branches, not Redis concurrency, TTL scheduling or networking.
"""
from pathlib import Path
import argparse
import copy
import json
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--runtime-path', type=Path)
parser.add_argument('--report', type=Path)
args = parser.parse_args()
if args.runtime_path:
    sys.path.insert(0, str(args.runtime_path.resolve()))
from lupa import LuaRuntime

root = Path(__file__).resolve().parents[1]
script_path = root / 'RedPacketService/src/main/resources/lua/receive-redpacket.lua'
script = script_path.read_text(encoding='utf-8')
keys = ['pool', 'record', 'state', 'grant', 'allow']

def run(state, user='2', now=500):
    lua = LuaRuntime(unpack_returned_tuples=True)
    state = copy.deepcopy(state)
    calls = []
    def call(command, key, *values):
        calls.append(command)
        value = state.get(key)
        if command == 'HMGET':
            return lua.table_from([(value or {}).get(field, False) for field in values])
        if command == 'HGET': return (value or {}).get(values[0], False)
        if command == 'GET': return value if value is not None else False
        if command == 'SISMEMBER': return int(values[0] in (value or set()))
        if command == 'HEXISTS': return int(values[0] in (value or {}))
        if command == 'EXISTS': return int(key in state)
        if command == 'LPOP':
            if not value: return False
            amount = value.pop(0)
            if not value: state.pop(key, None)
            return amount
        if command == 'HSET':
            state.setdefault(key, {})[values[0]] = values[1]
            return 1
        if command == 'EXPIRE': return int(key in state)
        if command == 'LLEN': return len(value or [])
        raise AssertionError(command)
    lua.globals().redis = lua.table_from({'call': call})
    lua.globals().KEYS = lua.table_from(keys)
    lua.globals().ARGV = lua.table_from([user, str(now)])
    result = lua.execute(script)
    return [result[i] for i in range(1, len(result) + 1)], state, calls

base = {'grant': {'sessionType':'0', 'senderId':'1', 'receiverId':'2', 'ready':'1', 'expiresAt':'1000'}, 'pool':['100', '200']}
cases = []
def case(name, data, expected, user='2', now=500, pops=0):
    result, after, calls = run(data, user, now)
    assert result == expected, (name, result, expected)
    assert calls.count('LPOP') == pops, (name, 'unexpected pool consumption')
    if pops == 0: assert after == data, (name, 'read-only response mutated state')
    else:
        assert after['record'][user] == '100'
        assert after['record:times'][user] == str(now)
    cases.append(name)

case('first claim writes amount and time', base, [100, 0], pops=1)
case('last claim reports completed', {**base, 'pool':['100']}, [100, 1], pops=1)
case('repeat returns original receipt while pool remains', {**base,'record':{'2':'100'}}, [-1,0])
case('repeat after clock expiry retains receipt', {**base,'record':{'2':'100'}}, [-1,2], now=1000)
case('repeat after grant expiry retains receipt and expired state', {'record':{'2':'100'},'state':'2'}, [-1,2])
case('repeat completed packet without grant retains receipt', {'record':{'2':'100'},'state':'1'}, [-1,1])
case('completed state wins over later clock expiry for repeat', {**base,'record':{'2':'100'},'state':'1'}, [-1,1], now=1000)
case('completed state wins over later clock expiry for new claim', {**base,'state':'1'}, [-2,1], now=1000)
case('completed tombstone without grant is not not-found', {'state':'1'}, [-2,1])
case('expired tombstone without grant is not not-found', {'state':'2'}, [-2,2])
case('pending transaction never consumes pool', {**base,'grant':{**base['grant'],'ready':'0'}}, [-3])
case('new claim cannot pass expiry boundary', base, [-2,2], now=1000)
case('single sender cannot claim own packet', base, [-4,1], user='1')
case('single outsider cannot claim', base, [-4,2], user='9')
group = {**base,'grant':{**base['grant'],'sessionType':'1'},'allow':{'1','2'}}
case('group snapshot member can claim', group, [100,0], pops=1)
case('group outsider is rejected', group, [-4,3], user='9')
case('no cache traces returns not-found', {}, [-3])
report = dict(date='2026-11-01', runtime='Lupa 2.6 / actual Lua source', redis='in-memory command stub; not real Redis', tests=len(cases), failures=0, scenarios=cases)
if args.report:
    args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(f'{len(cases)} Lua behavior scenarios passed; Redis calls are simulated.')

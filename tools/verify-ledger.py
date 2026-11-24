"""Controlled failure verification against the dedicated local demo database only."""
from pathlib import Path
import json, subprocess, urllib.request, time, uuid, sys

root=Path(__file__).resolve().parents[1]
result_path=Path(sys.argv[1])
integration=json.loads(result_path.read_text(encoding='utf-8'))
assert integration.get('completed'), 'Run verify-integrated.mjs successfully first.'
env={}
for line in (root/'.env').read_text(encoding='utf-8-sig').splitlines():
    if '=' in line and not line.startswith('#'):
        k,v=line.split('=',1);env[k]=v

compose_project = env.get('IM_COMPOSE_PROJECT') or 'qingliao-local'

def docker(args,data):
    p=subprocess.run(['wsl','-d','Ubuntu-20.04','-u','root','--exec','docker','exec','-i',*args],input=data.encode(),capture_output=True,timeout=20)
    if p.returncode:raise RuntimeError('Container verification command failed: '+p.stderr.decode('utf-8',errors='ignore')[-200:])
    return p.stdout.decode('utf-8',errors='replace').strip()
def sql(query):
    return docker([compose_project + '_mysql_1', 'sh', '-c',
                   'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -N -B -uroot "$1"',
                   'sh', env['DB_NAME']], query)
def redis(*args):
    # Password goes through stdin, never through the command line or report.
    return docker([compose_project + '_redis_1', 'sh', '-c',
                   'read -r REDISCLI_AUTH; export REDISCLI_AUTH; exec redis-cli -n 2 "$@"',
                   'sh', *map(str, args)], env['REDIS_PASSWORD'] + '\n')
def api(path,body,token=''):
    request=urllib.request.Request('http://localhost:10010'+path,data=json.dumps(body).encode(),headers={'Content-Type':'application/json','Access-Token':token})
    with urllib.request.urlopen(request,timeout=15) as r:response=json.load(r)
    assert response.get('code')==200, response.get('message')
    return response['data']
def until(fn,seconds=25):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        value=fn()
        if value:return value
        time.sleep(.3)
    raise AssertionError('Timed out waiting for ledger state')

packet=integration['ids']['redPacketId'];assert packet.isdigit()
assert sql(f'SELECT COUNT(*) FROM red_packet_receive WHERE red_packet_id={packet};')=='1'
assert sql(f'SELECT CONCAT(COUNT(*),":",SUM(amount)) FROM balance_log WHERE related_id={packet} AND type=1;')=='1:1'
print('PASS concurrent claim has one receipt and one credit of one fen')
token=api('/api/user/login/password',{'email':'demo-a@example.com','password':'123456'})['accessToken']
gift=api('/api/chat/redPacket/send',{'sessionId':'99001','receiverId':'2002','senderId':'1001','sessionType':0,'type':3,'body':{'redPacketType':0,'totalAmount':.03,'totalCount':1,'redPacketWrapperText':'controlled refund verification'},'clientMessageId':str(uuid.uuid4())},token)
packet=str(gift['redPacketId']);assert packet.isdigit()
trigger='verify_refund_'+packet
report={'received_packet':integration['ids']['redPacketId'],'refund_packet':packet,'claim_rows':1,'credit_rows':1,'credit_fen':1}
try:
    sql(f'''DELIMITER //
CREATE TRIGGER {trigger} BEFORE INSERT ON balance_log FOR EACH ROW
BEGIN
 IF NEW.type=2 AND NEW.related_id={packet} THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled local refund retry verification';
 END IF;
END//
DELIMITER ;
''')
    sql(f'UPDATE red_packet SET created_time=DATE_SUB(NOW(),INTERVAL 25 HOUR) WHERE red_packet_id={packet};')
    redis('HSET',f'redpacket:{packet}:grant','expiresAt','0')
    redis('ZADD','redpacket-expire-zset','0',packet)
    until(lambda:redis('GET',f'redpacket:{packet}:refund-amount')=='3')
    # Keep the failure active briefly so the refund attempt is observed as rolled back.
    time.sleep(1)
    assert sql(f'SELECT status FROM red_packet WHERE red_packet_id={packet};')=='0'
    assert sql(f'SELECT COUNT(*) FROM balance_log WHERE related_id={packet} AND type=2;')=='0'
    report['failed_refund_rollback_and_snapshot']=True
    print('PASS failed refund rolls back database and retains Redis refund snapshot')
finally:
    sql(f'DROP TRIGGER IF EXISTS {trigger};')

redis('ZADD','redpacket-expire-zset','0',packet)
until(lambda:sql(f'SELECT status FROM red_packet WHERE red_packet_id={packet};')=='2')
assert sql(f'SELECT CONCAT(COUNT(*),":",SUM(amount)) FROM balance_log WHERE related_id={packet} AND type=2;')=='1:3'
redis('ZADD','redpacket-expire-zset','0',packet)
time.sleep(2)
assert sql(f'SELECT CONCAT(COUNT(*),":",SUM(amount)) FROM balance_log WHERE related_id={packet} AND type=2;')=='1:3'
report.update(refund_rows=1,refund_fen=3,repeated_expiry_does_not_recredit=True)
result_path.with_name('ledger-result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print('PASS Kafka expiry retry completes refund once, repeated expiry does not recredit')

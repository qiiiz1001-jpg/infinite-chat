"""标准库检查原文映射、最终表字段、响应和部署材料；不替代运行测试。"""
from pathlib import Path
import hashlib,json,re,sys

ROOT=Path(__file__).resolve().parents[1]
manifest=json.loads((ROOT/'docs/reference-coverage.json').read_text(encoding='utf-8'))
source=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else ROOT.parents[2]/manifest['source']
if source.exists():
    assert hashlib.sha256(source.read_bytes()).hexdigest()==manifest['source_sha256'], '原文已变化，需要重新核对并更新映射'
else:
    print('原始课程文件不在当前副本；仅校验已有映射与工程材料。')
assert manifest['blocks']==len(manifest['entries'])==589
for entry in manifest['entries']:
    assert entry['artifacts'],entry['line']
    for path in entry['artifacts']:assert (ROOT/path).is_file(),(entry['line'],path)
sql=(ROOT/'database/01-init.sql').read_text(encoding='utf-8')
def table(name):
    return re.search(r'CREATE TABLE IF NOT EXISTS `?'+name+r'`?\s*\((.*?)\) ENGINE',sql,re.S).group(1)
assert '`avatar`' in table('session')
assert '`session_type`' in table('red_packet')
upgrade=(ROOT/'database/02-business-upgrade.sql').read_text(encoding='utf-8')
assert "column_name='avatar'" in upgrade and "column_name='session_type'" in upgrade
response=(ROOT/'UserService/src/main/java/cn/qingliao/userservice/model/dto/response/CreateGroupResponse.java').read_text(encoding='utf-8')
for field in ['sessionType','avatar','failedMemberIds']:assert re.search(r'\b'+field+r'\s*;',response),field
groups=(ROOT/'UserService/src/main/java/cn/qingliao/userservice/model/dto/UserGroupDTO.java').read_text(encoding='utf-8')
for field in ['name','memberCount','createdTime']:assert re.search(r'\b'+field+r'\s*;',groups),field
dashboard=json.loads((ROOT/'deploy/grafana/dashboards/ai-monitor.json').read_text(encoding='utf-8'))
assert len(dashboard['panels'])==10
assert (ROOT/'deploy/grafana/dashboards/provider.yml').is_file()
assert (ROOT/'deploy/compose-discovery.yml').is_file()
for mapper in ['UserService/src/main/resources/mapper/UserSessionMapper.xml',
               'UserService/src/main/resources/mapper/SessionMapper.xml',
               'OfflineDataService/src/main/resources/mapper/MessageMapper.xml']:
    assert (ROOT/mapper).is_file(),mapper
for module in ['UserService','AiService','OfflineDataService','RealTimeService','RedPacketService','GateWay']:
    for profile in ['application.yml','application-dev.yml','application-prod.yml']:
        assert (ROOT/module/'src/main/resources'/profile).is_file(),(module,profile)
    docker=(ROOT/module/'Dockerfile').read_text(encoding='utf-8')
    assert '/app/logs/nacos' in docker and 'USER app' in docker,module
print('原文映射 589 项、表字段、响应字段、10 个看板面板及六应用部署材料检查通过。')

// Run against the dedicated seeded local environment. Only test accounts and test balances are used.
import fs from 'node:fs';
const reportPath=process.argv[2]||'deploy/run/integration-result.json';
const base='http://localhost:10010',travel='http://localhost:10020/api';
const report={date:new Date().toISOString(),mode:'real middleware; model deferred',checks:[],ids:{}};
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const parse=t=>JSON.parse(t.replace(/(:\s*)(-?\d{16,})(?=\s*[,}])/g,'$1"$2"'));
function ok(name,detail){report.checks.push({name,passed:true,detail});console.log('PASS '+name);}
async function api(path,body,token='',origin=base){
 const response=await fetch(origin+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(origin===base?{'Access-Token':token}:{Authorization:'Bearer '+token})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});
 const raw=await response.text();let data;try{data=parse(raw)}catch{throw Error(path+' returned '+response.status+' '+raw.slice(0,120))}
 if(!response.ok||(data.code&&data.code!==200)||data.success===false)throw Error(path+': '+(data.message||data.errMsg||response.status));
 return data.data??data;
}
async function connect(url,token){const ws=new WebSocket(url,['im','bearer.'+token]);ws.messages=[];ws.addEventListener('message',e=>{if(e.data!=='pong')ws.messages.push(parse(e.data))});await new Promise((resolve,reject)=>{const t=setTimeout(()=>reject(Error('WebSocket handshake timeout')),12000);ws.addEventListener('open',()=>{clearTimeout(t);resolve()},{once:true});ws.addEventListener('error',()=>{clearTimeout(t);reject(Error('WebSocket handshake failed'))},{once:true})});return ws;}
async function until(fn,description,limit=18000){const end=Date.now()+limit;while(Date.now()<end){const v=await fn();if(v)return v;await pause(150)}throw Error('Timeout: '+description)}
let a,b;
try{
 const A=await api('/api/user/login/password',{email:'demo-a@example.com',password:'123456'});
 const B=await api('/api/user/login/password',{email:'demo-b@example.com',password:'123456'});
 ok('password login and trusted tokens');
 a=await connect('ws://localhost:9101/ws/netty',A.accessToken);
 b=await connect('ws://localhost:9102/ws/netty',B.accessToken);
 ok('browser-compatible authenticated WebSocket on two nodes');
 const text='cross-node-'+crypto.randomUUID();
 a.send(JSON.stringify({senderId:'9999',sessionId:'99001',receiverId:'2002',sessionType:0,type:0,body:{content:text},clientMessageId:crypto.randomUUID()}));
 const received=await until(()=>b.messages.find(m=>m.content===text||m.body?.content===text),'cross-node delivery');
 if(String(received.senderId)!=='1001')throw Error('Untrusted sender identity was accepted');
 report.ids.messageId=String(received.messageId);report.ids.text=text;
 ok('Kafka cross-node push and trusted sender override');
 await until(async()=>{const history=await api('/api/message/history',{sessionId:'99001',beforeTime:Date.now()+1000,limit:50},A.accessToken);return history.some(m=>String(m.messageId)===report.ids.messageId)},'persisted history');
 ok('authorized message history');
 let spoofRejected=false;
 try{await api('/api/group',{creatorId:'9999',memberIds:['2002']},A.accessToken)}catch{spoofRejected=true}
 if(!spoofRejected)throw Error('Spoofed group owner was accepted');ok('spoofed group owner rejected');
 const group=await api('/api/group',{creatorId:'1001',memberIds:['2002']},A.accessToken);
 report.ids.groupId=String(group.sessionId);
 const gt='group-'+crypto.randomUUID();
 a.send(JSON.stringify({sessionId:report.ids.groupId,receiverId:null,sessionType:1,type:0,body:{content:gt},clientMessageId:crypto.randomUUID()}));
 await until(()=>b.messages.some(m=>m.content===gt||m.body?.content===gt),'group message');
 ok('group creation and group message push');
 const gift=await api('/api/chat/redPacket/send',{sessionId:'99001',receiverId:'2002',senderId:'1001',sessionType:0,type:3,body:{redPacketType:0,totalAmount:0.01,totalCount:1,redPacketWrapperText:'local verification'},clientMessageId:crypto.randomUUID()},A.accessToken);
 report.ids.redPacketId=String(gift.redPacketId);
 const claims=await Promise.all(Array.from({length:8},()=>api('/api/chat/redPacket/receive',{userId:'2002',redPacketId:report.ids.redPacketId},B.accessToken).catch(e=>({error:e.message}))));
 report.claimStatuses=claims.map(x=>x.status??x.error);
 await until(async()=>{const result=await api('/api/chat/redPacket/?redPacketId='+report.ids.redPacketId,undefined,B.accessToken);return result.receivedCount===1||result.receivedCount==='1'},'red packet ledger settlement',25000);
 ok('concurrent duplicate claims settle once (database count checked separately)');
 a.send(JSON.stringify({sessionId:'99002',receiverId:'111111111',sessionType:2,type:0,body:{content:'infrastructure-check'},clientMessageId:crypto.randomUUID()}));
 await until(()=>a.messages.some(m=>(m.content||m.body?.content||'').includes('模型服务尚未配置')),'explicit infrastructure-mode AI status');
 ok('AI request topic and persisted status reply; no model answer claimed');
 const status=await api('/api/ai/status',undefined,A.accessToken);if(status.modelEnabled)throw Error('Expected explicitly deferred model mode');ok('model mode is explicitly disabled');
 const email='verify-'+Date.now()+'@example.com';const credentials={email,password:'LocalOnly123!'};
 await api('/user/register',{...credentials,username:'local-verification'},'',travel);
 const user=await api('/user/login',credentials,'',travel);
 const conversation=await api('/conversation/create',{},user.token,travel);
 report.ids.travelConversation=conversation.id||conversation.conversationId;
 const list=await api('/conversation/list',undefined,user.token,travel);if(!Array.isArray(list)||!list.length)throw Error('Travel conversation missing');
 ok('travel real PostgreSQL registration/conversations and Redis login');
 const deferred=await fetch(travel+'/travel/chat',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+user.token},body:JSON.stringify({sessionId:report.ids.travelConversation,prompt:'从北京去上海旅行'})});
 if(deferred.status!==503)throw Error('Model request must be rejected explicitly in infra mode');
 ok('travel model deferred response is explicit HTTP 503');
 const secondEmail='other-'+Date.now()+'@example.com';await api('/user/register',{email:secondEmail,password:credentials.password,username:'other-verification'},'',travel);
 const other=await api('/user/login',{email:secondEmail,password:credentials.password},'',travel);
 const foreign=await fetch(travel+'/conversation/messages?conversationId='+encodeURIComponent(report.ids.travelConversation),{headers:{Authorization:'Bearer '+other.token}});
 const denied=await foreign.json();if(denied.success!==false&&foreign.ok)throw Error('Foreign travel conversation was exposed');
 ok('travel conversation ownership restriction');
 report.completed=true;
}catch(error){report.completed=false;report.error=error.message;console.error('FAIL '+error.message);process.exitCode=1}
finally{a?.close();b?.close();fs.mkdirSync(new URL('.',new URL('file:///'+reportPath.replaceAll('\\','/'))),{recursive:true});fs.writeFileSync(reportPath,JSON.stringify(report,null,2));}

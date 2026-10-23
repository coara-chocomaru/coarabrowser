var browser=(function(){
var id=coara.id;
var manifestCache=null;
var runtimeListeners=[];
var changeListeners=[];
var sessionStore=Object.create(null);
var MAX_STORAGE_KEYS=256;
var MAX_STORAGE_KEY_LENGTH=128;
var MAX_STORAGE_VALUE_LENGTH=65536;
var MAX_STORAGE_TOTAL_LENGTH=524288;
var MAX_EVENT_LISTENERS=256;
var MAX_JSON_NODES=32768;
var MAX_CLONE_LENGTH=1048576;
var BLOCKED_KEYS=Object.create(null);
BLOCKED_KEYS["__proto__"]=true;
BLOCKED_KEYS.prototype=true;
BLOCKED_KEYS.constructor=true;
function boundedStringify(value,maxLength,maxNodes){
var active=typeof WeakSet!=="undefined"?new WeakSet():[];
var nodes=0;
var chars=0;
function inspect(v,depth){
if(++nodes>maxNodes||depth>64)return false;
if(typeof v==="string"){
chars+=v.length;
return chars<=maxLength;
}
if(v===null||typeof v==="undefined"||typeof v==="boolean"||typeof v==="number")return true;
if(typeof v!=="object")return true;
if(active instanceof Array){if(active.indexOf(v)>=0)return false;active.push(v);}
else{if(active.has(v))return false;active.add(v);}
var ok=true;
if(Array.isArray(v)){
var limit=Math.min(v.length,maxNodes-nodes);
for(var i=0;i<limit;i++)if(!inspect(v[i],depth+1)){ok=false;break;}
if(v.length>limit)ok=false;
}else{
var count=0;
for(var k in v){
if(!Object.prototype.hasOwnProperty.call(v,k))continue;
if(++count>maxNodes-nodes||k.length>MAX_STORAGE_KEY_LENGTH){ok=false;break;}
chars+=k.length;
if(chars>maxLength||!inspect(v[k],depth+1)){ok=false;break;}
}
}
if(active instanceof Array){var ix=active.lastIndexOf(v);if(ix>=0)active.splice(ix,1);}else active.delete(v);
return ok;
}
try{
if(!inspect(value,0))return null;
var raw=JSON.stringify(value);
return typeof raw==="string"&&raw.length<=maxLength?raw:null;
}catch(e){return null;}
}
function clone(v){
if(v===undefined)return undefined;
var raw=boundedStringify(v,MAX_CLONE_LENGTH,MAX_JSON_NODES);
if(raw===null)return undefined;
try{return JSON.parse(raw);}catch(e){return undefined;}
}
function limitedOwnKeys(value,limit){
var out=[];
if(value==null||(typeof value!=="object"&&typeof value!=="function"))return out;
try{
for(var k in value){
if(!Object.prototype.hasOwnProperty.call(value,k))continue;
if(out.length>=limit)break;
out.push(k);
}
}catch(e){}
return out;
}
function ret(value,cb){
if(typeof cb==="function"){setTimeout(function(){try{cb(value);}catch(e){coara._rep("runtime",e&&e.message?e.message:String(e),e&&e.stack);}},0);return undefined;}
return Promise.resolve(value);
}
function manifest(){
if(manifestCache===null){
var t=coara.getResource("manifest.json");
try{manifestCache=t?JSON.parse(t):{};}catch(e){manifestCache={};}
}
return manifestCache;
}
function getURL(p){
p=String(p==null?"":p).replace(/^\/+/,"");
var u=p?coara.getResourceURL(p):null;
return u||("chrome-extension://"+id+"/"+p);
}
function fire(changes,area){
var listeners=changeListeners.slice();
for(var i=0;i<listeners.length;i++){
try{listeners[i](clone(changes),area);}catch(e){}
}
}
function validKey(k){
return typeof k==="string"&&k.length>0&&k.length<=MAX_STORAGE_KEY_LENGTH&&!Object.prototype.hasOwnProperty.call(BLOCKED_KEYS,k);
}
function makeArea(name,store){
var P="ext:";
var readOnly=name==="managed";
function readOne(k){
if(!validKey(k))return undefined;
if(store)return Object.prototype.hasOwnProperty.call(store,k)?clone(store[k]):undefined;
var raw=coara.storage.get(P+k);
if(raw===null||raw===undefined)return undefined;
try{return JSON.parse(raw);}catch(e){return undefined;}
}
function serialise(v){
return boundedStringify(v,MAX_STORAGE_VALUE_LENGTH,8192);
}
function writeOne(k,v){
if(readOnly||!validKey(k))return false;
var raw=serialise(v);
if(raw===null)return false;
var ks=allKeys();
if(ks.indexOf(k)<0&&ks.length>=MAX_STORAGE_KEYS)return false;
var total=0;
for(var i=0;i<ks.length;i++){
if(ks[i]===k)continue;
var existing=store?JSON.stringify(store[ks[i]]):coara.storage.get(P+ks[i]);
if(existing!=null)total+=String(existing).length;
if(total>MAX_STORAGE_TOTAL_LENGTH)return false;
}
if(total+raw.length>MAX_STORAGE_TOTAL_LENGTH)return false;
if(store){
try{store[k]=JSON.parse(raw);return true;}catch(e){return false;}
}
return coara.storage.set(P+k,raw);
}
function removeOne(k){
if(readOnly||!validKey(k))return;
if(store)delete store[k];
else coara.storage.remove(P+k);
}
function allKeys(){
if(store)return Object.keys(store).filter(validKey);
var ks=coara.storage.keys(),out=[];
for(var i=0;i<ks.length;i++){
if(ks[i].indexOf(P)===0){var k=ks[i].substring(P.length);if(validKey(k))out.push(k);}
}
return out;
}
function putOwn(target,k,v){
Object.defineProperty(target,k,{value:v,writable:true,enumerable:true,configurable:true});
}
return{
get:function(keys,cb){
if(typeof keys==="function"){cb=keys;keys=null;}
var res={},i;
if(keys==null){
var ks=allKeys().slice(0,MAX_STORAGE_KEYS);
for(i=0;i<ks.length;i++)putOwn(res,ks[i],readOne(ks[i]));
}else if(typeof keys==="string"){
var v=readOne(keys);
if(validKey(keys)&&v!==undefined)putOwn(res,keys,v);
}else if(Array.isArray(keys)){
for(i=0;i<keys.length&&i<MAX_STORAGE_KEYS;i++){
var key=String(keys[i]);var v2=readOne(key);
if(validKey(key)&&v2!==undefined)putOwn(res,key,v2);
}
}else if(keys&&typeof keys==="object"){
var inputKeys=limitedOwnKeys(keys,MAX_STORAGE_KEYS);
for(var j=0;j<inputKeys.length;j++){
var k=inputKeys[j];if(!validKey(k))continue;
var v3=readOne(k);
putOwn(res,k,v3===undefined?clone(keys[k]):v3);
}
}
return ret(res,cb);
},
set:function(items,cb){
var changes={};
if(!readOnly&&items&&typeof items==="object"){
var inputKeys=limitedOwnKeys(items,MAX_STORAGE_KEYS);
for(var i=0;i<inputKeys.length;i++){
var k=inputKeys[i];if(!validKey(k))continue;
var old=readOne(k);
if(!writeOne(k,items[k]))continue;
putOwn(changes,k,{oldValue:old,newValue:clone(items[k])});
}
if(Object.keys(changes).length)fire(changes,name);
}
return ret(undefined,cb);
},
remove:function(keys,cb){
var list=(Array.isArray(keys)?keys:[keys]).slice(0,MAX_STORAGE_KEYS);
var changes={};
if(!readOnly){
for(var i=0;i<list.length;i++){
var key=String(list[i]);if(!validKey(key))continue;
var old=readOne(key);
removeOne(key);
putOwn(changes,key,{oldValue:old});
}
if(Object.keys(changes).length)fire(changes,name);
}
return ret(undefined,cb);
},
clear:function(cb){
var ks=allKeys(),changes=Object.create(null);
if(!readOnly){
for(var i=0;i<ks.length;i++){
putOwn(changes,ks[i],{oldValue:readOne(ks[i])});
removeOne(ks[i]);
}
if(Object.keys(changes).length)fire(changes,name);
}
return ret(undefined,cb);
},
getBytesInUse:function(keys,cb){
if(typeof keys==="function"){cb=keys;}
var ks=allKeys(),n=0;
if(keys!=null&&typeof keys!=="function"){
var selected=Array.isArray(keys)?keys.slice(0,MAX_STORAGE_KEYS):[keys];
ks=ks.filter(function(k){return selected.indexOf(k)>=0;});
}
for(var i=0;i<ks.length;i++){var raw=JSON.stringify(readOne(ks[i]));if(raw)n+=raw.length;}
return ret(n,typeof keys==="function"?keys:cb);
}
};
}
function event(list){
return{
addListener:function(f){if(typeof f==="function"&&list.indexOf(f)<0&&list.length<MAX_EVENT_LISTENERS)list.push(f);},
removeListener:function(f){var i=list.indexOf(f);if(i>=0)list.splice(i,1);},
hasListener:function(f){return list.indexOf(f)>=0;}
};
}
function sendMessage(){
var args=Array.prototype.slice.call(arguments,0,4);
var cb=typeof args[args.length-1]==="function"?args.pop():null;
var msg=(args.length>=2&&typeof args[0]==="string")?args[1]:args[0];
var p=new Promise(function(resolve){
var done=false,pending=0;
function respond(v){if(!done){done=true;resolve(v);}}
var sender={id:id,url:String(location.href),tab:{id:1,url:String(location.href),active:true}};
var ls=runtimeListeners.slice();
for(var i=0;i<ls.length&&!done;i++){
var r;
try{r=ls[i](clone(msg),sender,function(v){respond(clone(v));});}catch(e){continue;}
if(r===true){pending++;}
else if(r&&typeof r.then==="function"){pending++;r.then(function(v){respond(v);},function(){});}
}
if(!done){setTimeout(function(){respond(undefined);},pending?5000:0);}
});
if(cb){p.then(cb);return undefined;}
return p;
}
var onChanged=event(changeListeners);
var local=makeArea("local",null);
var sync=makeArea("sync",null);
var session=makeArea("session",sessionStore);
var lang=(coara.uiLanguage()||"en").replace("_","-");
var ext={
runtime:{
id:id,
lastError:null,
getURL:getURL,
getManifest:function(){return clone(manifest());},
sendMessage:sendMessage,
onMessage:event(runtimeListeners),
onInstalled:event([]),
onStartup:event([]),
onConnect:event([]),
connect:function(){
return{name:"",postMessage:function(){},disconnect:function(){},onMessage:event([]),onDisconnect:event([])};
},
getPlatformInfo:function(cb){return ret({os:"android",arch:"arm",nacl_arch:"arm"},cb);},
getBrowserInfo:function(cb){return ret({name:"CoaraBrowser",vendor:"coara",version:"1.0",buildID:"1"},cb);},
openOptionsPage:function(cb){return ret(undefined,cb);}
},
extension:{
getURL:getURL,
getBackgroundPage:function(){return null;},
inIncognitoContext:false
},
storage:{local:local,sync:sync,managed:makeArea("managed",{}),session:session,onChanged:onChanged},
i18n:{
getMessage:function(key,subs){
var arr=subs==null?null:(Array.isArray(subs)?subs:[subs]);
return coara.i18n(String(key),arr);
},
getUILanguage:function(){return lang;},
getAcceptLanguages:function(cb){return ret([lang],cb);}
},
tabs:{
create:function(props,cb){
var u=props&&props.url?String(props.url):"";
if(u)coara.openTab(u);
return ret({id:2,url:u,active:!(props&&props.active===false)},cb);
},
query:function(q,cb){
if(typeof q==="function"){cb=q;}
return ret([{id:1,url:String(location.href),title:String(document.title||""),active:true}],typeof q==="function"?q:cb);
},
getCurrent:function(cb){return ret({id:1,url:String(location.href),active:true},cb);},
sendMessage:function(tabId,msg,a,b){return sendMessage(msg,typeof a==="function"?a:b);},
update:function(tabId,props,cb){
if(typeof tabId==="object"){cb=props;props=tabId;}
if(props&&props.url&&/^https?:/i.test(String(props.url))){location.href=String(props.url);}
return ret({id:1,url:String(location.href)},cb);
}
},
notifications:{
create:function(a,b,c){
var nid=typeof a==="string"?a:"n"+Date.now();
var opts=typeof a==="string"?b:a;
var cb=typeof b==="function"?b:c;
if(opts)coara.notify(opts.message==null?"":String(opts.message),opts.title==null?undefined:String(opts.title));
return ret(nid,typeof cb==="function"?cb:undefined);
},
clear:function(nid,cb){return ret(true,cb);},
onClicked:event([]),
onClosed:event([])
}
};
return ext;
})();
var chrome=browser;

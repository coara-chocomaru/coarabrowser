var unsafeWindow=window;
var __gmMeta=coara.meta||{};
var GM_info={
script:{name:__gmMeta.title||"",namespace:__gmMeta.namespace||"",version:__gmMeta.version||"",description:__gmMeta.description||"",author:__gmMeta.author||"",homepage:__gmMeta.homepage||"",matches:__gmMeta.rawMatches||[],excludes:__gmMeta.rawExcludes||[],"run-at":__gmMeta.runAt||"",grant:__gmMeta.permissions||[],resources:[],uuid:__gmMeta.id||""},
scriptMetaStr:"",
scriptHandler:"CoaraBrowser",
version:"1.0",
isIncognito:false,
isFirstPartyIsolation:false
};
var __gmListeners=Object.create(null);
var __gmListenerSeq=0;
var __gmListenerCount=0;
var __gmMaxListeners=256;
var __gmMaxKeyLength=128;
var __gmMaxValueLength=65536;
var __gmMaxValueNodes=8192;
var __gmBlockedKeys=Object.create(null);
__gmBlockedKeys["__proto__"]=true;
__gmBlockedKeys.prototype=true;
__gmBlockedKeys.constructor=true;
function __gmValidKey(k){
return typeof k==="string"&&k.length>0&&k.length<=__gmMaxKeyLength&&!Object.prototype.hasOwnProperty.call(__gmBlockedKeys,k);
}
function __gmSerialise(value){
var active=typeof WeakSet!=="undefined"?new WeakSet():[];
var nodes=0;
var chars=0;
function inspect(v,depth){
if(++nodes>__gmMaxValueNodes||depth>64)return false;
if(typeof v==="string"){
chars+=v.length;
return chars<=__gmMaxValueLength;
}
if(v===null||typeof v==="undefined"||typeof v==="boolean"||typeof v==="number")return true;
if(typeof v!=="object")return true;
if(active instanceof Array){
if(active.indexOf(v)>=0)return false;
active.push(v);
}else{
if(active.has(v))return false;
active.add(v);
}
var ok=true;
if(Array.isArray(v)){
var limit=Math.min(v.length,__gmMaxValueNodes-nodes);
for(var i=0;i<limit;i++)if(!inspect(v[i],depth+1)){ok=false;break;}
if(v.length>limit)ok=false;
}else{
var count=0;
for(var k in v){
if(!Object.prototype.hasOwnProperty.call(v,k))continue;
if(++count>__gmMaxValueNodes-nodes||k.length>__gmMaxKeyLength){ok=false;break;}
chars+=k.length;
if(chars>__gmMaxValueLength||!inspect(v[k],depth+1)){ok=false;break;}
}
}
if(active instanceof Array){var ix=active.lastIndexOf(v);if(ix>=0)active.splice(ix,1);}else active.delete(v);
return ok;
}
try{
if(!inspect(value,0))return null;
var raw=JSON.stringify(value);
return typeof raw==="string"&&raw.length<=__gmMaxValueLength?raw:null;
}catch(e){return null;}
}
function __gmFire(name,oldV,newV){
var l=__gmListeners[name];
if(!l)return;
var ids=Object.keys(l);
for(var i=0;i<ids.length;i++){
var f=l[ids[i]];
if(typeof f!=="function")continue;
try{f(name,oldV,newV,false);}catch(e){coara._rep("runtime",e&&e.message?e.message:String(e),e&&e.stack);}
}
}
function GM_log(){coara.log.apply(null,arguments);}
function GM_addStyle(css){return coara.addStyle(css);}
function GM_getValue(k,d){
k=String(k);
if(!__gmValidKey(k))return d;
var raw=coara.storage.get(k);
if(raw===null||raw===undefined)return d;
if(typeof raw!=="string"||raw.length>__gmMaxValueLength)return d;
try{return JSON.parse(raw);}catch(e){return raw;}
}
function GM_setValue(k,v){
k=String(k);
if(!__gmValidKey(k))return false;
if(v===undefined){return GM_deleteValue(k);}
var raw=__gmSerialise(v);
if(raw===null)return false;
var old=GM_getValue(k);
var ok=coara.storage.set(k,raw);
if(ok)__gmFire(k,old,v);
return ok;
}
function GM_deleteValue(k){
k=String(k);
if(!__gmValidKey(k))return false;
var old=GM_getValue(k);
var ok=coara.storage.remove(k);
if(ok)__gmFire(k,old,undefined);
return !!ok;
}
function GM_listValues(){var keys=coara.storage.keys();if(!Array.isArray(keys))return [];var out=[];for(var i=0;i<keys.length&&out.length<256;i++){var k=String(keys[i]);if(__gmValidKey(k))out.push(k);}return out;}
function GM_addValueChangeListener(name,fn){
name=String(name);
if(typeof fn!=="function"||name.length>128||__gmListenerCount>=__gmMaxListeners)return 0;
var id=++__gmListenerSeq;
(__gmListeners[name]=__gmListeners[name]||Object.create(null))[id]=fn;
__gmListenerCount++;
return id;
}
function GM_removeValueChangeListener(id){
for(var n in __gmListeners){
if(__gmListeners[n][id]){delete __gmListeners[n][id];__gmListenerCount=Math.max(0,__gmListenerCount-1);}
}
}
function GM_getResourceText(n){return coara.getResource(String(n));}
function GM_getResourceURL(n){return coara.getResourceURL(String(n));}
function GM_xmlhttpRequest(d){return coara.xhr(d);}
function GM_setClipboard(text){coara.copy(String(text));}
function GM_notification(a,b,c,d){
var o=(a&&typeof a==="object")?a:{text:a,title:b,image:c,onclick:d};
coara.notify(o.text==null?"":String(o.text),o.title==null?undefined:String(o.title));
if(typeof o.ondone==="function"){setTimeout(function(){try{o.ondone();}catch(e){}},0);}
}
function GM_openInTab(url,opt){
coara.openTab(String(url));
return{closed:false,close:function(){},onclose:null};
}
function GM_registerMenuCommand(name,fn,key){return coara.registerCommand(name,fn);}
function GM_unregisterMenuCommand(id){coara.unregisterCommand(id);}
function GM_download(a,b){
var url=(a&&typeof a==="object")?a.url:a;
coara.openTab(String(url));
}
function __gmP(f){
return function(){
var args=arguments;
return new Promise(function(res,rej){
try{res(f.apply(null,args));}catch(e){rej(e);}
});
};
}
var GM={
info:GM_info,
log:GM_log,
getValue:__gmP(GM_getValue),
setValue:__gmP(GM_setValue),
deleteValue:__gmP(GM_deleteValue),
listValues:__gmP(GM_listValues),
addValueChangeListener:GM_addValueChangeListener,
removeValueChangeListener:GM_removeValueChangeListener,
getResourceText:__gmP(GM_getResourceText),
getResourceUrl:__gmP(GM_getResourceURL),
getResourceURL:__gmP(GM_getResourceURL),
addStyle:__gmP(GM_addStyle),
setClipboard:__gmP(GM_setClipboard),
notification:__gmP(GM_notification),
openInTab:__gmP(GM_openInTab),
registerMenuCommand:GM_registerMenuCommand,
unregisterMenuCommand:GM_unregisterMenuCommand,
download:__gmP(GM_download),
xmlHttpRequest:function(d){
var h;
var p=new Promise(function(res,rej){
var o={};
for(var k in d){o[k]=d[k];}
o.onload=function(r){res(r);};
o.onerror=function(r){rej(r);};
o.onabort=function(r){rej(r);};
o.ontimeout=function(r){rej(r);};
h=coara.xhr(o);
});
p.abort=function(){if(h)h.abort();};
return p;
}
};
GM.xmlhttpRequest=GM.xmlHttpRequest;

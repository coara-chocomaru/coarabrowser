(function(PLG){
var B=window["%BRIDGE%"];
var T="%TOKEN%";
var META=%META%;
var CSS=%CSS%;
var KEY="%KEY%";
var PKEY="%PKEY%";
var isTop=false;
try{isTop=window.top===window;}catch(e){}
function jparse(s){try{return JSON.parse(s);}catch(e){return null;}}
try{
var rs=window.__coaraPlgReady;
if(!rs){
rs={};
Object.defineProperty(window,"__coaraPlgReady",{value:rs,enumerable:false,configurable:true});
}
rs[KEY]=1;
}catch(e){}
function inv(op,a,b,c){
try{
if(B){
return B.invoke(T,META.id,op,a==null?"":String(a),b==null?"":String(b),c==null?"":String(c));
}
}catch(e){}
return null;
}
function rx(list,u){
for(var i=0;i<list.length;i++){
try{if(new RegExp(list[i]).test(u))return true;}catch(e){}
}
return false;
}
var href="";
try{href=String(location.href);}catch(e){}
if(href.length>8192)return"0";
if(!META.allFrames&&!isTop)return"0";
if(META.excludes.length&&rx(META.excludes,href))return"0";
if(!rx(META.matches,href))return"0";
var reports=0;
function rep(kind,message,stack){
if(reports>=5)return;
reports++;
var o={};
try{o.url=String(location.href);}catch(e){o.url="";}
o.stack=stack==null?"":String(stack).substring(0,3000);
inv("report",kind,message==null?"":String(message).substring(0,1000),JSON.stringify(o));
}
function fmt(args){
var out=[];
for(var i=0;i<args.length;i++){
var v=args[i];
if(typeof v==="string"){out.push(v);}
else{
try{
var s=JSON.stringify(v);
out.push(s===undefined?String(v):s);
}catch(e){out.push(String(v));}
}
}
return out.join(" ");
}
function safe(f){
try{f(api);}catch(e){rep("runtime",e&&e.message?e.message:String(e),e&&e.stack);}
}
var MAX_STYLE_COUNT=64;
var MAX_STYLE_CHARS=262144;
var MAX_STYLE_TOTAL_CHARS=524288;
var styleCount=0;
var styleChars=0;
function addStyle(css){
var text;
try{text=String(css);}catch(e){text="";}
if(text.length>MAX_STYLE_CHARS||styleCount>=MAX_STYLE_COUNT||styleChars+text.length>MAX_STYLE_TOTAL_CHARS)return{remove:function(){}};
styleCount++;
styleChars+=text.length;
var handle={};
var sheet=null;
var el=null;
var removed=false;
try{
if(typeof CSSStyleSheet==="function"&&("adoptedStyleSheets" in document)){
sheet=new CSSStyleSheet();
sheet.replaceSync(text);
document.adoptedStyleSheets=document.adoptedStyleSheets.concat([sheet]);
}
}catch(e){sheet=null;}
if(!sheet){
var attach=function(){
if(removed)return;
try{
el=document.createElement("style");
el.textContent=text;
(document.head||document.documentElement).appendChild(el);
}catch(e){}
};
if(document.head||document.documentElement){attach();}
else{document.addEventListener("DOMContentLoaded",attach,{once:true});}
}
handle.remove=function(){
if(removed)return;
removed=true;
styleCount=Math.max(0,styleCount-1);
styleChars=Math.max(0,styleChars-text.length);
try{
if(sheet){
document.adoptedStyleSheets=document.adoptedStyleSheets.filter(function(s){return s!==sheet;});
sheet=null;
}
if(el&&el.parentNode){
el.parentNode.removeChild(el);
el=null;
}
}catch(e){}
};
return handle;
}
function onReady(f){
if(typeof f!=="function")return;
if(document.readyState!=="loading"){setTimeout(function(){safe(f);},0);}
else{document.addEventListener("DOMContentLoaded",function(){safe(f);},{once:true});}
}
var cmds={};
var cmdSeq=0;
var cmdRoot=null;
try{
cmdRoot=window.__coaraPlgCmd;
if(!cmdRoot){
cmdRoot={};
Object.defineProperty(window,"__coaraPlgCmd",{value:cmdRoot,enumerable:false,configurable:true});
}
cmdRoot[KEY]=function(cid){var f=cmds[cid];if(typeof f==="function")safe(function(){f();});};
}catch(e){}
var cmdNames={};
var cmdArmed=false;
function resendCommands(){
for(var k in cmdNames){
if(Object.prototype.hasOwnProperty.call(cmdNames,k)&&typeof cmds[k]==="function")inv("cmd",k,cmdNames[k]);
}
}
function armCommandSync(){
if(cmdArmed)return;
cmdArmed=true;
try{
document.addEventListener("DOMContentLoaded",resendCommands);
window.addEventListener("load",resendCommands);
window.addEventListener("pageshow",resendCommands);
document.addEventListener("visibilitychange",function(){if(!document.hidden)resendCommands();});
setTimeout(resendCommands,300);
setTimeout(resendCommands,1200);
setTimeout(resendCommands,3500);
}catch(e){}
}
function registerCommand(name,fn){
if(!isTop||typeof fn!=="function"||Object.keys(cmds).length>=32)return null;
var cid=KEY+":"+(++cmdSeq);
cmds[cid]=fn;
cmdNames[cid]=String(name).substring(0,60);
inv("cmd",cid,cmdNames[cid]);
armCommandSync();
return cid;
}
function unregisterCommand(cid){
if(cid==null)return;
delete cmds[cid];
delete cmdNames[cid];
inv("uncmd",cid);
}
function toB64(buf){
var bytes=buf instanceof ArrayBuffer?new Uint8Array(buf):new Uint8Array(buf.buffer,buf.byteOffset,buf.byteLength);
var s="";
for(var i=0;i<bytes.length;i+=8192){s+=String.fromCharCode.apply(null,bytes.subarray(i,i+8192));}
return btoa(s);
}
function fromB64(b64){
var s=atob(b64);
var u=new Uint8Array(s.length);
for(var i=0;i<s.length;i++)u[i]=s.charCodeAt(i);
return u.buffer;
}
var xseq=0;
function xhr(o){
o=o||{};
var handle={abort:function(){}};
var finished=false;
var maxRequestBytes=2097152;
var maxOptionsChars=3000000;
if(!o.url||String(o.url).length>8192){
setTimeout(function(){if(typeof o.onerror==="function")o.onerror({error:"invalid url",status:0,readyState:4});},0);
return handle;
}
if(o.data!=null){
if(typeof o.data==="string"&&o.data.length>maxRequestBytes){
setTimeout(function(){if(typeof o.onerror==="function")o.onerror({error:"request body too large",status:0,readyState:4});},0);
return handle;
}
if(o.data instanceof ArrayBuffer&&o.data.byteLength>maxRequestBytes||o.data&&o.data.buffer instanceof ArrayBuffer&&typeof o.data.byteLength==="number"&&o.data.byteLength>maxRequestBytes){
setTimeout(function(){if(typeof o.onerror==="function")o.onerror({error:"request body too large",status:0,readyState:4});},0);
return handle;
}
}
function emit(name,payload){
if(finished&&name!=="onabort")return;
finished=true;
var f=o[name];
if(typeof f==="function"){try{f(payload);}catch(e){rep("runtime",e&&e.message?e.message:String(e),e&&e.stack);}}
}
var url;
try{url=new URL(String(o.url),location.href).href;}catch(e){
setTimeout(function(){emit("onerror",{error:"invalid url",status:0,readyState:4});},0);
return handle;
}
var inputHeaders=o.headers&&typeof o.headers==="object"?o.headers:{};
var headerKeys=Object.keys(inputHeaders);
if(headerKeys.length>64){
setTimeout(function(){if(typeof o.onerror==="function")o.onerror({error:"too many headers",status:0,readyState:4});},0);
return handle;
}
var checkedHeaders=Object.create(null);
var headerChars=0;
for(var hi=0;hi<headerKeys.length;hi++){
var hk=headerKeys[hi],hv=String(inputHeaders[hk]);
if(hk.length>256||hv.length>8192||/[\r\n]/.test(hk)||/[\r\n]/.test(hv)||!/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(hk)){
setTimeout(function(){if(typeof o.onerror==="function")o.onerror({error:"invalid headers",status:0,readyState:4});},0);
return handle;
}
headerChars+=hk.length+hv.length;
if(headerChars>32768){
setTimeout(function(){if(typeof o.onerror==="function")o.onerror({error:"headers too large",status:0,readyState:4});},0);
return handle;
}
checkedHeaders[hk]=hv;
}
var opts={url:url,method:o.method||"GET",headers:checkedHeaders,responseType:o.responseType||"",timeout:o.timeout||0,anonymous:!!o.anonymous,user:o.user||"",password:o.password||"",binary:!!o.binary,noRedirect:o.redirect==="manual"||o.redirect==="error"};
if(o.overrideMimeType&&/charset=([\w-]+)/i.test(o.overrideMimeType))opts.charset=RegExp.$1;
var d=o.data;
if(d!=null){
if(typeof d==="string")opts.data=d;
else if(typeof URLSearchParams!=="undefined"&&d instanceof URLSearchParams)opts.data=d.toString();
else if(d instanceof ArrayBuffer||(d&&d.buffer instanceof ArrayBuffer&&typeof d.byteLength==="number")){
var binaryLength=typeof d.byteLength==="number"?d.byteLength:0;
if(binaryLength>maxRequestBytes){setTimeout(function(){if(typeof o.onerror==="function")o.onerror({error:"request body too large",status:0,readyState:4});},0);return handle;}
opts.dataB64=toB64(d);
}
else opts.data=String(d);
if((opts.data&&opts.data.length>maxRequestBytes)||(opts.dataB64&&opts.dataB64.length>2800000)){
setTimeout(function(){if(typeof o.onerror==="function")o.onerror({error:"request body too large",status:0,readyState:4});},0);
return handle;
}
}
var rid=KEY+"x"+(++xseq)+Math.floor(Math.random()*1679616).toString(36);
var optsJson;
try{optsJson=JSON.stringify(opts);}catch(e){optsJson="";}
if(!optsJson||optsJson.length>maxOptionsChars){
setTimeout(function(){emit("onerror",{error:"request options too large",status:0,readyState:4});},0);
return handle;
}
var ok=inv("xhr",rid,optsJson);
if(ok!=="1"){
setTimeout(function(){emit("onerror",{error:"request rejected (permission/limit)",status:0,readyState:4});},0);
return handle;
}
var delay=25;
var timer=0;
function build(res){
var out={readyState:4,status:res.status||0,statusText:res.statusText||"",finalUrl:res.finalUrl||url,responseHeaders:res.headers||"",context:o.context};
if(res.b64!==undefined){
var ab=fromB64(res.b64);
out.response=opts.responseType==="blob"&&typeof Blob!=="undefined"?new Blob([ab],{type:res.mime||""}):ab;
out.responseText="";
}else{
out.responseText=res.text||"";
if(opts.responseType==="json"){out.response=jparse(out.responseText);}
else out.response=out.responseText;
}
return out;
}
function poll(){
if(finished)return;
var r=inv("xhrPoll",rid);
if(r===null||r===undefined){emit("onerror",{error:"lost request",status:0,readyState:4});return;}
if(r===""){delay=Math.min(Math.floor(delay*1.5),300);timer=setTimeout(poll,delay);return;}
var res=jparse(r);
if(!res){emit("onerror",{error:"bad response",status:0,readyState:4});return;}
if(res.aborted){emit("onabort",{error:"aborted",status:0,readyState:4});return;}
if(res.error){
var tag=/Timeout/i.test(res.error)?"ontimeout":"onerror";
emit(tag,{error:res.error,status:0,readyState:4,context:o.context});
return;
}
var b=build(res);
emit("onload",b);
if(typeof o.onloadend==="function"){try{o.onloadend(b);}catch(e){}}
}
timer=setTimeout(poll,delay);
handle.abort=function(){
if(finished)return;
inv("xhrAbort",rid);
clearTimeout(timer);
emit("onabort",{error:"aborted",status:0,readyState:4});
};
return handle;
}
var api={
id:META.id,
title:META.title,
version:META.version,
meta:META,
isTop:isTop,
url:href,
log:function(){inv("log","info",fmt(arguments));},
warn:function(){inv("log","warn",fmt(arguments));},
error:function(){inv("log","error",fmt(arguments));},
storage:{
get:function(k){var v=inv("sget",k);return v===undefined?null:v;},
set:function(k,v){return inv("sset",k,v)==="1";},
remove:function(k){return inv("sdel",k)==="1";},
keys:function(){return jparse(inv("skeys"))||[];},
clear:function(){return inv("sclear")==="1";}
},
getResource:function(n){return inv("res",n);},
getResourceURL:function(n){return inv("resurl",n);},
i18n:function(k,subs){var v=inv("i18n",k,subs==null?"":JSON.stringify(subs));return v==null?"":v;},
uiLanguage:function(){return inv("uilang")||"";},
addStyle:addStyle,
onReady:onReady,
xhr:xhr,
copy:function(t){return inv("clip",t)==="1";},
notify:function(text,title){return inv("notify",title==null?META.title:title,text==null?"":text)==="1";},
openTab:function(u){return inv("open",u)==="1";},
registerCommand:registerCommand,
unregisterCommand:unregisterCommand,
_inv:inv,
_safe:safe,
_rep:rep,
_fmt:fmt
};
var SU="coara-plugin/"+META.id+"/";
window.addEventListener("error",function(ev){
try{
var f=String(ev&&ev.filename||"");
if(f.indexOf(SU)<0)return;
rep("error",ev.message,ev.error&&ev.error.stack);
}catch(e){}
},true);
window.addEventListener("unhandledrejection",function(ev){
try{
var r=ev&&ev.reason;
var s=String(r&&r.stack||"");
if(s.indexOf(SU)<0)return;
rep("promise",(r&&r.message)?r.message:String(r),s);
}catch(e){}
},true);
var ran=false;
function run(){
if(ran)return;
ran=true;
try{
for(var i=0;i<CSS.length;i++){api.addStyle(CSS[i]);}
PLG.call(window,api);
inv("ok","","","");
}catch(e){
rep("runtime",e&&e.message?e.message:String(e),e&&e.stack);
}
}
var at=META.runAt;
if(at==="document_start"){
run();
}else if(at==="document_idle"){
var idle=function(){setTimeout(run,0);};
if(document.readyState==="complete"){idle();}
else{window.addEventListener("load",idle,{once:true});}
}else{
if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",run,{once:true});}
else{run();}
}
return"1";
})(function(coara){
%PRELUDE%
%BODY%
});
//# sourceURL=coara-plugin/%ID%/plugin.js

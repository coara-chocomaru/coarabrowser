package com.coara.browser.webview;

import android.net.Uri;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Collections;
import java.util.Locale;

public final class CookieGate {
    public static final String BRIDGE_NAME = "CoaraCookieGate";
    public static final String KIND_COOKIE = "c";
    public static final String KIND_STORAGE_ACCESS = "s:";

    public interface Listener {
        void onRequest(WebView view, String frameOrigin, String message, JavaScriptReplyProxy reply);
    }

    private static final String SCRIPT = "(function(){try{"
            + "if(window.top===window)return;"
            + "var o=window.CoaraCookieGate;"
            + "if(!o||typeof o.postMessage!=='function')return;"
            + "var sent=0,seq=0,wait={};"
            + "var send=function(k){if(sent>=2)return;sent++;try{o.postMessage(k);}catch(e){}};"
            + "o.onmessage=function(ev){try{"
            + "var d=String(ev.data),i=d.indexOf(':');if(i<1)return;"
            + "var w=wait[d.slice(0,i)];if(!w)return;delete wait[d.slice(0,i)];"
            + "if(d.slice(i+1)==='1')w[0]();else w[1]();"
            + "}catch(e){}};"
            + "var D=Object.getOwnPropertyDescriptor(Document.prototype,'cookie');"
            + "if(D&&D.configurable&&D.get&&D.set){"
            + "Object.defineProperty(Document.prototype,'cookie',{configurable:true,enumerable:D.enumerable,"
            + "get:function(){return D.get.call(this);},"
            + "set:function(v){"
            + "D.set.call(this,v);"
            + "try{if(sent<2){"
            + "var s=String(v),n=s.split(';')[0],q=n.indexOf('='),nm=(q<0?n:n.slice(0,q)).trim();"
            + "if(nm&&!/max-age\\s*=\\s*(0|-)|expires\\s*=[^;]*(1970|1969)/i.test(s)){"
            + "var c=D.get.call(this);"
            + "if((';'+c.replace(/;\\s*/g,';')).indexOf(';'+nm+'=')<0)send('c');"
            + "}"
            + "}}catch(e){}"
            + "}});"
            + "}"
            + "var P=Document.prototype;"
            + "if(typeof P.requestStorageAccess==='function'){"
            + "P.requestStorageAccess=function(){"
            + "return new Promise(function(res,rej){"
            + "var id=String(++seq);"
            + "wait[id]=[function(){res();},function(){rej(new DOMException('Storage access denied','NotAllowedError'));}];"
            + "try{o.postMessage('s:'+id);}catch(e){delete wait[id];rej(new DOMException('Storage access denied','NotAllowedError'));}"
            + "});"
            + "};"
            + "}"
            + "}catch(e){}})();";

    private CookieGate() {
    }

    public static boolean attach(WebView view, final Listener listener) {
        if (view == null || listener == null) {
            return false;
        }
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                    || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                return false;
            }
            WebViewCompat.addWebMessageListener(view, BRIDGE_NAME, Collections.singleton("*"),
                    new WebViewCompat.WebMessageListener() {
                        @Override
                        public void onPostMessage(@NonNull WebView v, @NonNull WebMessageCompat message,
                                                  @NonNull Uri sourceOrigin, boolean isMainFrame,
                                                  @NonNull JavaScriptReplyProxy replyProxy) {
                            if (isMainFrame) {
                                return;
                            }
                            String data = message.getData();
                            if (data == null || data.length() > 24) {
                                return;
                            }
                            if (!KIND_COOKIE.equals(data) && !data.startsWith(KIND_STORAGE_ACCESS)) {
                                return;
                            }
                            listener.onRequest(v, sourceOrigin.toString(), data, replyProxy);
                        }
                    });
            WebViewCompat.addDocumentStartJavaScript(view, SCRIPT, Collections.singleton("*"));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void reply(JavaScriptReplyProxy proxy, String storageMessage, boolean allow) {
        if (proxy == null || storageMessage == null || !storageMessage.startsWith(KIND_STORAGE_ACCESS)) {
            return;
        }
        String id = storageMessage.substring(KIND_STORAGE_ACCESS.length());
        if (id.isEmpty() || id.length() > 12) {
            return;
        }
        try {
            proxy.postMessage(id + ":" + (allow ? "1" : "0"));
        } catch (Throwable ignored) {
        }
    }

    public static String siteOf(String host) {
        if (host == null) {
            return "";
        }
        String h = host.toLowerCase(Locale.US);
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.isEmpty() || h.indexOf(':') >= 0 || h.indexOf('.') < 0) {
            return h;
        }
        String[] labels = h.split("\\.");
        int n = labels.length;
        if (n <= 2) {
            return h;
        }
        boolean numeric = true;
        for (String l : labels) {
            if (l.isEmpty() || !Character.isDigit(l.charAt(0))) {
                numeric = false;
                break;
            }
        }
        if (numeric) {
            return h;
        }
        String last = labels[n - 1];
        String second = labels[n - 2];
        boolean compound = last.length() == 2 && (second.equals("co") || second.equals("com") || second.equals("ne")
                || second.equals("or") || second.equals("go") || second.equals("ac") || second.equals("ad")
                || second.equals("ed") || second.equals("gr") || second.equals("lg") || second.equals("net")
                || second.equals("org") || second.equals("gov") || second.equals("edu"));
        int keep = compound ? 3 : 2;
        StringBuilder sb = new StringBuilder();
        for (int i = n - keep; i < n; i++) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(labels[i]);
        }
        return sb.toString();
    }
}

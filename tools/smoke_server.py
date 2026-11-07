#!/usr/bin/env python3
"""Dependency-free Android WebView smoke fixture; never calls a model API."""
import argparse
import base64
import json
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

SESSIONS = {}
LOCK = threading.Lock()
PAGE = r'''<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>酒馆口袋 · 后台烟测</title>
<style>body{font:16px system-ui;background:#111820;color:#e8edf2;max-width:700px;margin:auto;padding:24px}button,input{font:inherit;padding:12px;margin:6px 0}button{background:#d5b57b;border:0;border-radius:8px}a{color:#9ed2ff}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#202b37;padding:16px;min-height:90px}.hint{color:#b3bfcc}</style>
<h1>后台连接烟测</h1><p class="hint">启动后切到后台或锁屏三分钟，再回来检查是否收到完整编号。此页不调用 AI，不会收费。刷新不会自动重试，页面计数会清空；服务端只在内存中保留本次测试状态。</p>
<label>持续秒数 <input id="seconds" type="number" min="1" max="3600" value="180"></label><br>
<button id="start">开始流式测试</button> <button id="stop" disabled>主动停止</button>
<p id="status">尚未开始</p><pre id="output"></pre>
<p><label>文件上传选择 <input id="file" type="file" multiple></label></p><p id="files"></p>
<button id="download">下载 Blob JSON</button>
<p><a href="/link" target="_blank" rel="noopener">在新窗口打开链接</a></p>
<script>
let controller=null,session=null;
const $=id=>document.getElementById(id);
$('file').onchange=()=>{$('files').textContent=Array.from($('file').files).map(f=>f.name+' ('+f.size+' 字节)').join('，')||'未选择文件';};
$('download').onclick=()=>{const blob=new Blob([JSON.stringify({fixture:'jiuguan',session,time:new Date().toISOString()},null,2)],{type:'application/json'});const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download='jiuguan-smoke.json';a.click();URL.revokeObjectURL(url);};
$('stop').onclick=()=>controller&&controller.abort();
$('start').onclick=async()=>{
  const seconds=Math.max(1,Math.min(3600,Number($('seconds').value)||180));
  controller=new AbortController();session=null;$('output').textContent='';$('start').disabled=true;$('stop').disabled=false;
  try{const r=await fetch('/stream?seconds='+seconds,{signal:controller.signal,cache:'no-store'});if(!r.ok)throw Error('HTTP '+r.status);
    const reader=r.body.getReader();const decoder=new TextDecoder();let buffer='',count=0,done=false;
    while(true){const item=await reader.read();if(item.done)break;buffer+=decoder.decode(item.value,{stream:true});let i;
      while((i=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,i);buffer=buffer.slice(i+1);if(!line)continue;const row=JSON.parse(line);session=row.session;
        if(row.type==='tick'){count=row.index;$('output').textContent+=row.index+' ';$('status').textContent='接收中：'+count+'/'+seconds+' · '+session;}
        if(row.type==='complete'){done=true;$('status').textContent='完整完成：'+count+'/'+seconds+' · '+session;}
      }
    }
    if(!done)$('status').textContent='连接提前结束：'+count+'/'+seconds+' · '+session;
  }catch(e){$('status').textContent=(e.name==='AbortError'?'主动停止':'连接出错：'+e.message)+(session?' · '+session:'');}
  finally{$('start').disabled=false;$('stop').disabled=true;controller=null;}
};
</script></html>'''


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def auth_ok(self):
        if not self.server.require_auth:
            return True
        expected = "Basic " + base64.b64encode(b"test:test").decode("ascii")
        if self.headers.get("Authorization") == expected:
            return True
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="Jiuguan smoke"')
        self.send_header("Content-Length", "0")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        return False

    def respond(self, status, data, content_type="text/html; charset=utf-8"):
        data = data.encode("utf-8") if isinstance(data, str) else data
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if not self.auth_ok():
            return
        parsed = urlsplit(self.path)
        if parsed.path == "/":
            return self.respond(200, PAGE)
        if parsed.path == "/link":
            return self.respond(200, '<meta charset="utf-8"><h1>新窗口链接已打开</h1><a href="/">返回测试页</a>')
        if parsed.path == "/sessions":
            with LOCK:
                data = json.dumps(SESSIONS, ensure_ascii=False)
            return self.respond(200, data, "application/json; charset=utf-8")
        if parsed.path != "/stream":
            return self.respond(404, "Not found", "text/plain")
        try:
            seconds = max(1, min(3600, int(parse_qs(parsed.query).get("seconds", [180])[0])))
        except ValueError:
            return self.respond(400, "Invalid seconds", "text/plain")
        session = uuid.uuid4().hex[:12]
        with LOCK:
            SESSIONS[session] = {"seconds": seconds, "sent": 0, "status": "streaming"}
            while len(SESSIONS) > 100:
                del SESSIONS[next(iter(SESSIONS))]
        self.log_message("STREAM START session=%s seconds=%d", session, seconds)
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson; charset=utf-8")
        self.send_header("Transfer-Encoding", "chunked")
        self.send_header("Cache-Control", "no-store, no-transform")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()
        try:
            for index in range(1, seconds + 1):
                self.chunk({"type": "tick", "session": session, "index": index})
                with LOCK:
                    SESSIONS[session]["sent"] = index
                time.sleep(1)
            self.chunk({"type": "complete", "session": session, "count": seconds})
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
            with LOCK:
                SESSIONS[session]["status"] = "complete"
            self.log_message("STREAM COMPLETE session=%s sent=%d", session, seconds)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, TimeoutError, OSError):
            with LOCK:
                SESSIONS[session]["status"] = "disconnected"
                sent = SESSIONS[session]["sent"]
            self.close_connection = True
            self.log_message("STREAM DISCONNECTED session=%s sent=%d/%d", session, sent, seconds)

    def chunk(self, value):
        data = (json.dumps(value, ensure_ascii=False) + "\n").encode("utf-8")
        self.wfile.write(('%X\r\n' % len(data)).encode("ascii") + data + b"\r\n")
        self.wfile.flush()


def make_server(host="127.0.0.1", port=8765, auth=False):
    server = ThreadingHTTPServer((host, port), Handler)
    server.daemon_threads = True
    server.require_auth = auth
    return server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--auth", action="store_true", help="require Basic login test/test")
    args = parser.parse_args()
    server = make_server(args.host, args.port, args.auth)
    print("Smoke fixture: http://%s:%d/ (Basic auth: %s)" % (args.host, server.server_port, "test/test" if args.auth else "off"), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()

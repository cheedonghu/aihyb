"""TLS CONNECT only for this PoC's fixed COS endpoint. No TLS interception, secrets or path logs."""
import http.server
import select
import socket
import threading
import time

ALLOWED={'cos.ap-shanghai.myqcloud.com','pt-1305414590.cos.ap-shanghai.myqcloud.com'}
class Server(http.server.ThreadingHTTPServer):
    daemon_threads=True
    request_queue_size=8
    slots=threading.BoundedSemaphore(8)
    def process_request(self,request,address):
        if not self.slots.acquire(False):request.close();return
        try:super().process_request(request,address)
        except Exception:self.slots.release();raise
    def process_request_thread(self,request,address):
        try:super().process_request_thread(request,address)
        finally:self.slots.release()
class Proxy(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def setup(self):super().setup();self.connection.settimeout(15)
    def log_message(self,*args):pass
    def do_CONNECT(self):
        host,separator,port=self.path.rpartition(':')
        if separator!=':' or port!='443' or host.lower() not in ALLOWED:
            self.send_error(403,'Destination not allowed');return
        try:
            with socket.create_connection((host,443),timeout=10) as upstream:
                self.send_response(200,'Connection Established');self.end_headers()
                peers=(self.connection,upstream);last=time.monotonic()
                while time.monotonic()-last<30:
                    readable,_,_=select.select(peers,[],[],2)
                    for source in readable:
                        block=source.recv(64*1024)
                        if not block:return
                        target=upstream if source is self.connection else self.connection
                        target.sendall(block);last=time.monotonic()
        except (OSError,TimeoutError):return
    def do_GET(self):self.send_error(405,'CONNECT only')
if __name__=='__main__':Server(('172.29.240.1',18091),Proxy).serve_forever()

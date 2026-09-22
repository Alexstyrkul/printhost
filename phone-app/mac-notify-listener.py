#!/usr/bin/env python3
"""Tiny local listener for PrintHost's Mac notifications.

Run it on the Mac while printing; PrintHost auto-discovers it by scanning the
local subnet for this /ping signature (no IP/port to type in anywhere), then
POSTs to /done or /error when the print finishes or the printer stops
responding, which triggers a native macOS notification via `osascript`.

Usage:
    python3 mac-notify-listener.py [port]   # default port 8765 - must match
                                             # PrinterService.MAC_NOTIFY_PORT
"""
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

# Must match dev.oleksandr.printhost.MacNotifier.PING_SIGNATURE exactly - this
# is how the subnet scan tells "the real listener" apart from anything else
# that happens to answer on the same port.
PING_SIGNATURE = "printhost-notify-v1"

MESSAGES = {
    "done": ("PrintHost", "Print finished", "Glass"),
    "error": ("PrintHost", "Printer stopped responding", "Basso"),
}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.split("?")[0] == "/ping":
            body = PING_SIGNATURE.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        event = self.path.strip("/").split("?")[0]
        title, message, sound = MESSAGES.get(event, ("PrintHost", "Event: " + event, "Pop"))
        script = 'display notification "{}" with title "{}" sound name "{}"'.format(
            message.replace('"', "'"), title.replace('"', "'"), sound
        )
        subprocess.run(["osascript", "-e", script], check=False)
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, fmt, *args):
        print("[mac-notify]", fmt % args)


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    server = HTTPServer(("0.0.0.0", port), Handler)
    print(f"Listening on 0.0.0.0:{port} - PrintHost will find this automatically.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()

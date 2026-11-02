// Kleiner Webserver für www/ (Test am PC: Simulator oder Web Serial in Chrome).
//   node scripts/serve.mjs [port]   →  http://localhost:8080
import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { extname, join, normalize, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(fileURLToPath(new URL("../www/", import.meta.url)));
const port = Number(process.argv[2] || process.env.PORT || 8080);
const types = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
};

createServer(async (req, res) => {
  try {
    const url = new URL(req.url, "http://localhost");
    let p = normalize(join(root, decodeURIComponent(url.pathname)));
    if (!p.startsWith(root)) throw new Error("forbidden");
    if ((await stat(p)).isDirectory()) p = join(p, "index.html");
    const body = await readFile(p);
    res.writeHead(200, { "Content-Type": types[extname(p)] || "application/octet-stream", "Cache-Control": "no-store" });
    res.end(body);
  } catch {
    res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
    res.end("nicht gefunden");
  }
}).listen(port, () => console.log(`AP-Finder: http://localhost:${port}`));

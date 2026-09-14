#!/usr/bin/env python3
"""
Mock source to try out the product service. Only uses the Python standard library.

  GET  /api/products?page=0&size=50     products (all product data + price), in the source's own format
  POST /api/webhooks                    register a webhook: {"url": "...", "secret": "..."}
  GET  /api/webhooks                    registered webhooks
  POST /api/price-changes?count=3       change some prices now and call the webhooks
  POST /api/product-changes?count=1     change some product data now and call the webhooks
  POST /api/new-products                release the next new product (new-products.csv) and call the webhooks
  GET  /health

Every CHANGE_INTERVAL seconds (default 10, 0 = off) a few prices change, every third time a product's data
changes too and every sixth time a new product is released. Each change is sent to every registered webhook,
signed with X-Signature: sha256=<hex HMAC-SHA256 of the body with the secret>.

Run it with: python3 mock-source/server.py   (port 9090, or set PORT)
"""
import copy
import csv
import hashlib
import hmac
import json
import logging
import os
import random
import threading
import time
import uuid
from datetime import datetime, timedelta, timezone
from decimal import ROUND_HALF_UP, Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlparse
from urllib.request import Request, urlopen

PORT = int(os.environ.get("PORT", "9090"))
CHANGE_INTERVAL = float(os.environ.get("CHANGE_INTERVAL", "10"))
PRODUCTS_FILE = Path(__file__).with_name("products.csv")
NEW_PRODUCTS_FILE = Path(__file__).with_name("new-products.csv")

EXTRA_DETAILS = [
    "Free returns within 30 days.",
    "Comes with a 2-year warranty.",
    "Now also delivered in Belgium.",
    "Packaging made from recycled cardboard.",
]
AVAILABILITY = ["In stock", "Low stock", "Back in stock soon"]

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-7s %(message)s")
log = logging.getLogger("mock-source")


def utc_now():
    return datetime.now(timezone.utc)


def iso(moment):
    return moment.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def read_csv(path, released):
    with path.open(encoding="utf-8", newline="") as file:
        return [from_row(row, released) for row in csv.DictReader(file)]


def from_row(row, released):
    if released:
        # pretend the product data was last changed some weeks ago and the price in the last few days
        last_modified = iso(utc_now() - timedelta(days=random.randint(7, 60)))
        price_valid_from = iso(utc_now() - timedelta(minutes=random.randint(60, 3 * 24 * 60)))
    else:
        last_modified = price_valid_from = None  # set when the product gets released
    specs = [spec.split("=", 1) for spec in row["specs"].split(";") if "=" in spec]
    return {
        "articleNumber": row["articleNumber"],
        "title": row["title"],
        "details": row["details"],
        "manufacturer": row["manufacturer"],
        "categoryPath": row["categoryPath"],
        "specs": [{"name": name, "value": value} for name, value in specs],
        "price": {"amount": row["price"], "currency": row["currency"]},
        "priceValidFrom": price_valid_from,
        "lastModified": last_modified,
    }


class Catalog:
    def __init__(self):
        self._lock = threading.Lock()
        self._products = read_csv(PRODUCTS_FILE, released=True)
        self._upcoming = read_csv(NEW_PRODUCTS_FILE, released=False)
        log.info("loaded %d products, %d more to be released later", len(self._products), len(self._upcoming))

    def page(self, page, size):
        with self._lock:
            total = len(self._products)
            items = copy.deepcopy(self._products[page * size:(page + 1) * size])
        return {"items": items, "page": page, "size": size, "totalItems": total,
                "totalPages": (total + size - 1) // size}

    # a price change only touches the price, not the product's lastModified
    def change_random_prices(self, count):
        changes = []
        with self._lock:
            for product in random.sample(self._products, min(count, len(self._products))):
                old = Decimal(product["price"]["amount"])
                new = (old * Decimal(str(random.uniform(0.9, 1.1)))).quantize(Decimal("0.01"), ROUND_HALF_UP)
                valid_from = iso(utc_now())
                product["price"]["amount"] = str(new)
                product["priceValidFrom"] = valid_from
                changes.append({
                    "articleNumber": product["articleNumber"],
                    "price": {"amount": str(new), "currency": product["price"]["currency"]},
                    "validFrom": valid_from,
                })
                log.info("price of %s changed from %s to %s", product["articleNumber"], old, new)
        return changes

    def change_random_products(self, count):
        changed = []
        with self._lock:
            for product in random.sample(self._products, min(count, len(self._products))):
                availability = random.choice(AVAILABILITY)
                product["specs"] = [spec for spec in product["specs"] if spec["name"] != "availability"]
                product["specs"].append({"name": "availability", "value": availability})
                extra = random.choice(EXTRA_DETAILS)
                if extra not in product["details"]:
                    product["details"] = product["details"] + " " + extra
                product["lastModified"] = iso(utc_now())
                changed.append(copy.deepcopy(product))
                log.info("product %s changed: availability %s, details '%s'",
                         product["articleNumber"], availability, extra)
        return changed

    def release_new_product(self):
        with self._lock:
            if not self._upcoming:
                return None
            product = self._upcoming.pop(0)
            product["lastModified"] = product["priceValidFrom"] = iso(utc_now())
            self._products.append(product)
            log.info("new product %s released: %s", product["articleNumber"], product["title"])
            return copy.deepcopy(product)


class Webhooks:
    def __init__(self):
        self._lock = threading.Lock()
        self._secrets = {}  # url -> secret

    def register(self, url, secret):
        with self._lock:
            is_new = url not in self._secrets
            self._secrets[url] = secret
        if is_new:
            log.info("registered webhook %s", url)
        return is_new

    def urls(self):
        with self._lock:
            return list(self._secrets)

    def send(self, product_changes=(), price_changes=()):
        if not product_changes and not price_changes:
            return
        body = json.dumps({
            "eventId": str(uuid.uuid4()),
            "sentAt": iso(utc_now()),
            "productChanges": list(product_changes),
            "priceChanges": list(price_changes),
        }).encode()
        with self._lock:
            targets = list(self._secrets.items())
        for url, secret in targets:
            signature = "sha256=" + hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
            threading.Thread(target=deliver, args=(url, body, signature), daemon=True).start()


def deliver(url, body, signature, attempts=4):
    # like a real webhook sender: retry with back-off on network errors and 5xx, not on 4xx
    for attempt in range(1, attempts + 1):
        try:
            request = Request(url, data=body, method="POST",
                              headers={"Content-Type": "application/json", "X-Signature": signature})
            with urlopen(request, timeout=5) as response:
                log.info("webhook %s answered %d %s", url, response.status, response.read().decode()[:200])
                return
        except HTTPError as error:
            if error.code < 500:
                log.warning("webhook %s rejected the call with %d, not retrying", url, error.code)
                return
            log.warning("webhook %s failed with %d (attempt %d of %d)", url, error.code, attempt, attempts)
        except (URLError, OSError) as error:
            log.warning("webhook %s unreachable: %s (attempt %d of %d)", url, error, attempt, attempts)
        if attempt < attempts:
            time.sleep(2 ** attempt)
    log.error("giving up on webhook %s", url)


class Handler(BaseHTTPRequestHandler):

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/api/products":
            params = parse_qs(url.query)
            try:
                page = int(params.get("page", ["0"])[0])
                size = int(params.get("size", ["50"])[0])
            except ValueError:
                return self._json(400, {"error": "page and size must be numbers"})
            if page < 0 or not 1 <= size <= 200:
                return self._json(400, {"error": "page must be 0 or more, size between 1 and 200"})
            return self._json(200, catalog.page(page, size))
        if url.path == "/api/webhooks":
            return self._json(200, [{"url": url} for url in webhooks.urls()])
        if url.path == "/health":
            return self._json(200, {"status": "UP"})
        return self._json(404, {"error": "not found"})

    def do_POST(self):
        url = urlparse(self.path)
        if url.path == "/api/webhooks":
            try:
                body = json.loads(self._read_body() or b"{}")
            except ValueError:
                return self._json(400, {"error": "body must be JSON"})
            if not isinstance(body, dict) or not body.get("url") or not body.get("secret"):
                return self._json(400, {"error": "url and secret are required"})
            is_new = webhooks.register(body["url"], body["secret"])
            return self._json(201 if is_new else 200, {"url": body["url"]})
        if url.path in ("/api/price-changes", "/api/product-changes"):
            try:
                count = max(1, min(int(parse_qs(url.query).get("count", ["1"])[0]), 100))
            except ValueError:
                return self._json(400, {"error": "count must be a number"})
            if url.path == "/api/price-changes":
                changes = catalog.change_random_prices(count)
                webhooks.send(price_changes=changes)
                return self._json(200, {"priceChanges": changes})
            changes = catalog.change_random_products(count)
            webhooks.send(product_changes=changes)
            return self._json(200, {"productChanges": changes})
        if url.path == "/api/new-products":
            product = catalog.release_new_product()
            if product is None:
                return self._json(409, {"error": "all new products have been released already"})
            webhooks.send(product_changes=[product])
            return self._json(201, {"productChanges": [product]})
        return self._json(404, {"error": "not found"})

    def _read_body(self):
        # Java's HTTP client sends the body in chunks when it doesn't know the length up front
        if "chunked" not in self.headers.get("Transfer-Encoding", "").lower():
            return self.rfile.read(int(self.headers.get("Content-Length", 0)))
        body = b""
        while True:
            size = int(self.rfile.readline().split(b";")[0].strip(), 16)
            if size == 0:
                while self.rfile.readline() not in (b"\r\n", b"\n", b""):
                    pass
                return body
            body += self.rfile.read(size)
            self.rfile.readline()

    def _json(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        log.debug(format, *args)


def simulate_changes():
    tick = 0
    while True:
        time.sleep(CHANGE_INTERVAL)
        tick += 1
        product_changes = []
        if tick % 3 == 0:
            product_changes += catalog.change_random_products(1)
        if tick % 6 == 0:
            released = catalog.release_new_product()
            if released:
                product_changes.append(released)
        webhooks.send(product_changes, catalog.change_random_prices(random.randint(1, 3)))


if __name__ == "__main__":
    catalog = Catalog()
    webhooks = Webhooks()
    if CHANGE_INTERVAL > 0:
        threading.Thread(target=simulate_changes, daemon=True).start()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    log.info("mock source running on http://localhost:%d", PORT)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass

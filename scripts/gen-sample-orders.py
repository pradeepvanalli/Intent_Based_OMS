#!/usr/bin/env python3
"""Generates the Margin Replay sample files (deterministic, no dependencies):

  docs/examples/data/shopify-orders-sample.csv   ~300 orders / 30 days in Shopify "Orders export" layout
  docs/examples/data/generic-orders-sample.csv   50 line rows in the generic layout

Mostly demo-tenant SKUs (config/tenants/demo/catalog.yaml) with ~15 % unknown SKUs, US cities weighted to the coasts,
~10 % of orders carrying a free-text note (gift / urgent / together / pickup), a few PO boxes, two non-US orders, one
cancelled order and a couple of line items without a SKU, so the parser edge cases are exercised by the bundled sample.

Usage: python3 scripts/gen-sample-orders.py [--out docs/examples/data]
"""
import argparse
import csv
import datetime as dt
import os
import random

SEED = 20250901
START = dt.datetime(2025, 9, 1, 8, 0, 0)
DAYS = 30

# sku, name, price  (demo catalogue; see config/tenants/demo/catalog.yaml) with a relative weight
CATALOG = [
    ("TEE-BLK-M", "Organic cotton tee black M", 28.00, 9), ("TEE-WHT-L", "Organic cotton tee white L", 28.00, 7), ("HOOD-GRY-M", "Fleece hoodie grey M", 68.00, 7),
    ("JEAN-IND-32", "Selvedge denim indigo 32", 128.00, 4), ("SNK-RUN-10", "Trail runner sneaker 10", 145.00, 4), ("BOOT-LTH-9", "Leather chelsea boot 9", 220.00, 2),
    ("JKT-DWN-L", "Down puffer jacket L", 260.00, 2), ("BAG-TOTE", "Canvas tote", 45.00, 5), ("CAP-NVY", "Wool cap navy", 32.00, 6), ("SOCK-3PK", "Merino socks 3-pack", 36.00, 8),
    ("CNDL-SOY", "Soy candle 8oz", 34.00, 5), ("PERF-50ML", "Eau de parfum 50 ml", 120.00, 2), ("AERO-DEO", "Aerosol deodorant", 14.00, 3), ("KNIFE-CHEF", "Chef knife 8in", 95.00, 2),
    ("PWRBNK-20K", "20,000 mAh power bank", 59.00, 3), ("HEAD-BT", "Bluetooth headphones", 199.00, 3), ("WATCH-GPS", "GPS sports watch", 349.00, 1), ("BLND-PRO", "Pro blender", 179.00, 2),
    ("COFF-MKR", "Drip coffee maker", 89.00, 2), ("MUG-CER", "Ceramic mug", 18.00, 6), ("PAN-CI-12", "Cast iron pan 12in", 48.00, 3), ("LAMP-DSK", "LED desk lamp", 65.00, 3),
    ("RUG-5X8", "Wool rug 5x8", 320.00, 1), ("YOGA-MAT", "Yoga mat", 40.00, 4), ("DUMB-10KG", "Dumbbell 10 kg pair", 70.00, 2), ("BOTL-INS", "Insulated bottle", 35.00, 6),
    ("TENT-2P", "2-person tent", 240.00, 1), ("BOOK-COOK", "Cookbook hardcover", 35.00, 4), ("PUZZ-1000", "1000-piece puzzle", 22.00, 3), ("TOY-BLOCK", "Wooden block set", 45.00, 3),
    ("SKIN-SRM", "Vitamin C serum", 58.00, 4), ("SUN-SPF50", "Sunscreen SPF50", 24.00, 4), ("VIT-D3", "Vitamin D3 supplement", 19.00, 3), ("FINAL-SALE", "Clearance scarf (final sale)", 12.00, 3),
]
UNKNOWN = [  # not in the catalogue: COGS will be assumed by the replay
    ("SCARF-WOOL-RED", "Wool scarf red", 38.00), ("BEANIE-GRN", "Rib beanie green", 26.00), ("TOTE-LEATHER", "Leather tote", 140.00), ("CANDLE-SET-3", "Candle trio gift set", 54.00),
    ("PLANTER-CER-L", "Ceramic planter large", 42.00), ("NOTEBOOK-A5", "A5 dot notebook", 18.00), ("SUNGL-RND", "Round sunglasses", 95.00), ("SLIPPER-WOOL-M", "Wool slippers M", 64.00),
]
# city, state, zip, weight  (weighted to the coasts)
CITIES = [
    ("New York", "NY", "10001", 14), ("Brooklyn", "NY", "11201", 6), ("Los Angeles", "CA", "90012", 12), ("San Francisco", "CA", "94105", 7), ("San Diego", "CA", "92101", 5),
    ("Seattle", "WA", "98101", 7), ("Portland", "OR", "97205", 4), ("Boston", "MA", "02116", 6), ("Philadelphia", "PA", "19103", 4), ("Washington", "DC", "20001", 4),
    ("Miami", "FL", "33131", 5), ("Jersey City", "NJ", "07302", 3), ("Baltimore", "MD", "21201", 2), ("Oakland", "CA", "94612", 3), ("San Jose", "CA", "95113", 3),
    ("Sacramento", "CA", "95814", 2), ("Tampa", "FL", "33602", 2), ("Orlando", "FL", "32801", 2), ("Atlanta", "GA", "30303", 4), ("Charlotte", "NC", "28202", 2),
    ("Chicago", "IL", "60611", 6), ("Dallas", "TX", "75201", 4), ("Houston", "TX", "77002", 4), ("Austin", "TX", "78701", 4), ("San Antonio", "TX", "78205", 2),
    ("Denver", "CO", "80202", 3), ("Phoenix", "AZ", "85004", 3), ("Las Vegas", "NV", "89101", 2), ("Salt Lake City", "UT", "84101", 1), ("Minneapolis", "MN", "55401", 2),
    ("Detroit", "MI", "48226", 1), ("Nashville", "TN", "37203", 2), ("Kansas City", "MO", "64105", 1), ("St. Louis", "MO", "63101", 1), ("Columbus", "OH", "43215", 1),
    ("Boise", "ID", "83702", 1), ("Albuquerque", "NM", "87102", 1), ("Omaha", "NE", "68102", 1), ("Honolulu", "HI", "96813", 1), ("Anchorage", "AK", "99501", 1),
]
STREETS = ["Market St", "Michigan Ave", "Main St", "Broadway", "Pine St", "Elm St", "Ocean Dr", "Peachtree St", "Lake Shore Dr", "5th Ave", "Mission St", "Congress Ave"]
FIRST = ["Jane", "Omar", "Priya", "Luis", "Mei", "Noah", "Ava", "Kwame", "Sofia", "Ethan", "Grace", "Mateo", "Hana", "Liam", "Zoe", "Arjun"]
LAST = ["Doe", "Haddad", "Natarajan", "Ortega", "Chen", "Okafor", "Rossi", "Mensah", "Novak", "Park", "Kim", "Silva", "Brown", "Nguyen", "Patel", "Larsen"]
NOTES = [
    ("together", "Please ship everything together in one delivery"),
    ("together", "One box please, I don't want to track several parcels"),
    ("urgent", "I need the sneakers before Friday, the rest can come later"),
    ("urgent", "Urgent - birthday on Saturday, please rush whatever you can"),
    ("gift", "This is a gift for my sister, please no receipt in the box"),
    ("gift", "Gift for mom, please no prices on the packing slip.\nThanks a lot!"),
    ("separate", "Send each item as soon as it is ready, I do not mind separate boxes"),
    ("pickup", "Can I pick this up in your downtown store instead?"),
    ("none", "Leave the parcel with the concierge"),
    ("none", "Ring the bell twice, dog is friendly"),
]
SHOPIFY_COLUMNS = [
    "Name", "Email", "Financial Status", "Paid at", "Fulfillment Status", "Fulfilled at", "Accepts Marketing", "Currency", "Subtotal", "Shipping", "Taxes", "Total", "Discount Code",
    "Discount Amount", "Shipping Method", "Created at", "Lineitem quantity", "Lineitem name", "Lineitem price", "Lineitem compare at price", "Lineitem sku", "Lineitem requires shipping",
    "Lineitem taxable", "Lineitem fulfillment status", "Billing Name", "Billing Street", "Billing Address1", "Billing Address2", "Billing Company", "Billing City", "Billing Zip",
    "Billing Province", "Billing Country", "Billing Phone", "Shipping Name", "Shipping Street", "Shipping Address1", "Shipping Address2", "Shipping Company", "Shipping City", "Shipping Zip",
    "Shipping Province", "Shipping Country", "Shipping Phone", "Notes", "Note Attributes", "Cancelled at", "Payment Method", "Payment Reference", "Refunded Amount", "Vendor",
    "Outstanding Balance", "Employee", "Location", "Device ID", "Id", "Tags", "Risk Level", "Source", "Lineitem discount", "Tax 1 Name", "Tax 1 Value", "Phone", "Receipt Number", "Duties",
    "Billing Province Name", "Shipping Province Name", "Payment ID", "Payment Terms Name", "Next Payment Due At", "Payment References",
]
PROVINCE_NAMES = {"NY": "New York", "CA": "California", "WA": "Washington", "OR": "Oregon", "MA": "Massachusetts", "PA": "Pennsylvania", "DC": "District of Columbia", "FL": "Florida",
                  "NJ": "New Jersey", "MD": "Maryland", "GA": "Georgia", "NC": "North Carolina", "IL": "Illinois", "TX": "Texas", "CO": "Colorado", "AZ": "Arizona", "NV": "Nevada",
                  "UT": "Utah", "MN": "Minnesota", "MI": "Michigan", "TN": "Tennessee", "MO": "Missouri", "OH": "Ohio", "ID": "Idaho", "NM": "New Mexico", "NE": "Nebraska", "HI": "Hawaii",
                  "AK": "Alaska", "ON": "Ontario", "": ""}
TAX = {"CA": 0.0725, "NY": 0.04, "NJ": 0.06625, "TX": 0.0625, "IL": 0.0625, "WA": 0.065, "FL": 0.06, "GA": 0.04, "NV": 0.0685}
TZ = {"NY": "-0400", "MA": "-0400", "PA": "-0400", "DC": "-0400", "FL": "-0400", "NJ": "-0400", "MD": "-0400", "GA": "-0400", "NC": "-0400", "MI": "-0400", "OH": "-0400",
      "CA": "-0700", "WA": "-0700", "OR": "-0700", "NV": "-0700", "ID": "-0600", "AZ": "-0700", "UT": "-0600", "CO": "-0600", "NM": "-0600", "TX": "-0500", "IL": "-0500",
      "MN": "-0500", "TN": "-0500", "MO": "-0500", "NE": "-0500", "HI": "-1000", "AK": "-0800"}


def weighted(rnd, items):
    total = sum(i[-1] for i in items)
    r = rnd.random() * total
    for i in items:
        r -= i[-1]
        if r <= 0:
            return i
    return items[-1]


def money(v):
    return f"{v:.2f}"


def build_orders(rnd, count):
    orders = []
    for n in range(count):
        number = 1001 + n
        day = rnd.random() * DAYS
        created = START + dt.timedelta(days=day, hours=rnd.randint(0, 13), minutes=rnd.randint(0, 59), seconds=rnd.randint(0, 59))
        first, last = rnd.choice(FIRST), rnd.choice(LAST)
        email = f"{first}.{last}{rnd.randint(1, 99)}@example.com".lower()
        city, state, zipc, _ = weighted(rnd, CITIES)
        country = "US"
        tags = rnd.choice(["", "", "", "", "vip", "gold", "wholesale", "newsletter"])
        line_count = weighted(rnd, [(1, 55), (2, 30), (3, 12), (4, 3)])[0]
        lines, used = [], set()
        while len(lines) < line_count:
            if rnd.random() < 0.15:
                sku, name, price = rnd.choice(UNKNOWN)
            else:
                sku, name, price, _ = weighted(rnd, CATALOG)
            if sku in used:
                continue
            used.add(sku)
            qty = weighted(rnd, [(1, 80), (2, 16), (3, 4)])[0]
            lines.append({"sku": sku, "name": name, "price": price, "qty": qty, "requires_shipping": "true"})
        method_roll = rnd.random()
        method = "Standard Shipping" if method_roll < 0.76 else "Express Shipping" if method_roll < 0.9 else "Overnight" if method_roll < 0.95 else "Economy"
        if city in ("Los Angeles", "Chicago", "Austin") and rnd.random() < 0.35:
            method = "Local Pickup"
        subtotal = sum(l["price"] * l["qty"] for l in lines)
        discount_code, discount = "", 0.0
        if rnd.random() < 0.10:
            discount_code, discount = "FREESHIP", 0.0
        shipping = 0.0 if method == "Local Pickup" else 26.00 if method == "Overnight" else 14.00 if method == "Express Shipping" else (0.0 if subtotal >= 75 or discount_code == "FREESHIP" else 6.00)
        if discount_code == "FREESHIP":
            shipping = 0.0
        taxes = round(subtotal * TAX.get(state, 0.0), 2)
        total = round(subtotal + shipping + taxes - discount, 2)
        note = ""
        if rnd.random() < 0.10:
            note = rnd.choice(NOTES)[1]
        address1 = f"{rnd.randint(100, 9999)} {rnd.choice(STREETS)}"
        orders.append({
            "name": f"#{number}", "id": 5_900_000_000_000 + number * 7919, "email": email, "created": created, "first": first, "last": last, "city": city, "state": state, "zip": zipc,
            "country": country, "tags": tags, "lines": lines, "method": method, "subtotal": subtotal, "shipping": shipping, "taxes": taxes, "total": total, "discount_code": discount_code,
            "discount": discount, "note": note, "address1": address1, "cancelled": "", "phone": f"+1415555{rnd.randint(0, 9999):04d}",
        })
    # Edge cases, deterministic positions.
    orders[17]["address1"], orders[17]["city"], orders[17]["state"], orders[17]["zip"] = "PO Box 4412", "Reno", "NV", "89501"
    orders[64]["address1"] = "P.O. Box 902"
    orders[131]["address1"] = "PO BOX 77"
    orders[198]["address1"] = "Post Office Box 1510"
    orders[233]["address1"] = "PO Box 36"
    orders[41].update({"city": "Toronto", "state": "ON", "zip": "M5V 2T6", "country": "CA"})
    orders[150].update({"city": "London", "state": "", "zip": "SW1A 1AA", "country": "GB"})
    orders[88]["cancelled"] = (orders[88]["created"] + dt.timedelta(hours=2)).strftime("%Y-%m-%d %H:%M:%S -0400")
    orders[23]["lines"][0]["sku"] = ""  # line without a SKU
    orders[107]["lines"][-1]["sku"] = ""
    orders[12]["lines"].append({"sku": "GIFT-CARD", "name": "Gift card (digital)", "price": 50.00, "qty": 1, "requires_shipping": "false"})
    orders[12]["subtotal"] += 50.00
    orders[12]["total"] += 50.00
    orders[5]["note"] = 'Gift for my brother, "surprise" party on the 14th, please no receipt'
    orders[9]["note"] = "Please ship everything together in one delivery"
    orders[30]["note"] = "I need the sneakers before Friday, the rest can come later"
    orders[30]["lines"] = [{"sku": "SNK-RUN-10", "name": "Trail runner sneaker 10", "price": 145.00, "qty": 1, "requires_shipping": "true"},
                           {"sku": "SOCK-3PK", "name": "Merino socks 3-pack", "price": 36.00, "qty": 2, "requires_shipping": "true"}]
    orders[30]["subtotal"] = 145.00 + 72.00
    orders[30]["taxes"] = round(orders[30]["subtotal"] * TAX.get(orders[30]["state"], 0.0), 2)
    orders[30]["total"] = round(orders[30]["subtotal"] + orders[30]["shipping"] + orders[30]["taxes"], 2)
    # A handful of low-value, free-shipping orders that sit below a 12 % margin floor once shipped cross-country.
    for idx, sku in ((50, "FINAL-SALE"), (77, "MUG-CER"), (120, "AERO-DEO"), (160, "VIT-D3"), (210, "DUMB-10KG"), (260, "PAN-CI-12")):
        item = next(c for c in CATALOG if c[0] == sku)
        o = orders[idx]
        o["lines"] = [{"sku": item[0], "name": item[1], "price": item[2], "qty": 1, "requires_shipping": "true"}]
        o["discount_code"], o["discount"], o["shipping"], o["method"] = "FREESHIP", 0.0, 0.0, "Standard Shipping"
        o["subtotal"] = item[2]
        o["taxes"] = round(o["subtotal"] * TAX.get(o["state"], 0.0), 2)
        o["total"] = round(o["subtotal"] + o["taxes"], 2)
    orders.sort(key=lambda o: o["created"])
    return orders


def write_shopify(orders, path):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, quoting=csv.QUOTE_MINIMAL)
        w.writerow(SHOPIFY_COLUMNS)
        for o in orders:
            created = o["created"].strftime("%Y-%m-%d %H:%M:%S ") + TZ.get(o["state"], "-0400")
            paid = (o["created"] + dt.timedelta(seconds=40)).strftime("%Y-%m-%d %H:%M:%S ") + TZ.get(o["state"], "-0400")
            name = f'{o["first"]} {o["last"]}'
            for i, l in enumerate(o["lines"]):
                row = {c: "" for c in SHOPIFY_COLUMNS}
                row["Name"] = o["name"]
                row["Lineitem quantity"] = l["qty"]
                row["Lineitem name"] = l["name"]
                row["Lineitem price"] = money(l["price"])
                row["Lineitem sku"] = l["sku"]
                row["Lineitem requires shipping"] = l["requires_shipping"]
                row["Lineitem taxable"] = "true"
                row["Lineitem fulfillment status"] = "pending"
                row["Lineitem discount"] = "0.00"
                row["Vendor"] = "Intent Demo Co"
                if i == 0:
                    row.update({
                        "Email": o["email"], "Financial Status": "paid", "Paid at": paid, "Fulfillment Status": "unfulfilled", "Accepts Marketing": "yes", "Currency": "USD",
                        "Subtotal": money(o["subtotal"]), "Shipping": money(o["shipping"]), "Taxes": money(o["taxes"]), "Total": money(o["total"]), "Discount Code": o["discount_code"],
                        "Discount Amount": money(o["discount"]), "Shipping Method": o["method"], "Created at": created,
                        "Billing Name": name, "Billing Street": o["address1"], "Billing Address1": o["address1"], "Billing City": o["city"], "Billing Zip": o["zip"], "Billing Province": o["state"],
                        "Billing Country": o["country"], "Billing Phone": o["phone"], "Shipping Name": name, "Shipping Street": o["address1"], "Shipping Address1": o["address1"],
                        "Shipping City": o["city"], "Shipping Zip": o["zip"], "Shipping Province": o["state"], "Shipping Country": o["country"], "Shipping Phone": o["phone"],
                        "Notes": o["note"], "Cancelled at": o["cancelled"], "Payment Method": "Shopify Payments", "Payment Reference": f'c{o["id"]}.1', "Refunded Amount": "0.00",
                        "Outstanding Balance": "0.00", "Id": o["id"], "Tags": o["tags"], "Risk Level": "Low", "Source": "web", "Tax 1 Name": f'{o["state"]} State Tax' if o["taxes"] else "",
                        "Tax 1 Value": money(o["taxes"]) if o["taxes"] else "", "Phone": o["phone"], "Billing Province Name": PROVINCE_NAMES.get(o["state"], o["state"]),
                        "Shipping Province Name": PROVINCE_NAMES.get(o["state"], o["state"]), "Payment ID": f'r{o["id"]}',
                    })
                w.writerow([row[c] for c in SHOPIFY_COLUMNS])


def write_generic(rnd, path, rows_wanted=50):
    cols = ["order_id", "placed_at", "sku", "qty", "unit_price", "city", "state", "zip", "country", "ship_method", "note", "customer_tier", "shipping_paid", "email"]
    out, n, rows = [], 0, 0
    while rows < rows_wanted:
        n += 1
        oid = f"G-{2000 + n}"
        placed = (START + dt.timedelta(days=rnd.random() * DAYS, hours=rnd.randint(0, 12))).strftime("%Y-%m-%dT%H:%M:%SZ")
        city, state, zipc, _ = weighted(rnd, CITIES)
        count = min(rows_wanted - rows, weighted(rnd, [(1, 60), (2, 30), (3, 10)])[0])
        method = rnd.choice(["ground", "ground", "ground", "two_day", "next_day"])
        note = rnd.choice(NOTES)[1] if rnd.random() < 0.15 else ""
        tier = rnd.choice(["standard", "standard", "standard", "gold", "silver"])
        used = set()
        subtotal = 0.0
        lines = []
        for _ in range(count):
            if rnd.random() < 0.15:
                sku, name, price = rnd.choice(UNKNOWN)
            else:
                sku, name, price, _ = weighted(rnd, CATALOG)
            if sku in used:
                continue
            used.add(sku)
            qty = weighted(rnd, [(1, 80), (2, 20)])[0]
            lines.append((sku, qty, price))
            subtotal += qty * price
        shipping = 0.0 if subtotal >= 75 else 6.0
        for sku, qty, price in lines:
            out.append([oid, placed, sku, qty, money(price), city, state, zipc, "US", method, note, tier, money(shipping), f"buyer{n}@example.com"])
            rows += 1
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, quoting=csv.QUOTE_MINIMAL)
        w.writerow(cols)
        w.writerows(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "examples", "data"))
    ap.add_argument("--orders", type=int, default=300)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    rnd = random.Random(SEED)
    orders = build_orders(rnd, a.orders)
    write_shopify(orders, os.path.join(a.out, "shopify-orders-sample.csv"))
    write_generic(random.Random(SEED + 1), os.path.join(a.out, "generic-orders-sample.csv"))
    print(f"wrote {len(orders)} Shopify orders ({sum(len(o['lines']) for o in orders)} line rows) and the generic sample to {os.path.abspath(a.out)}")


if __name__ == "__main__":
    main()

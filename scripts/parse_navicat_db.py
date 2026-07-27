# -*- coding: utf-8 -*-
"""解析 navicat-db (MySQL dump) 的 INSERT 语句 → car_sku.csv
只保留知识库/客服需要的业务字段，成本类字段保留用于内部参考。
"""
import csv, re, sys

SRC = r"D:\idea\project\AI\navicat-db"
DST = r"D:\idea\project\AI\src\main\resources\data\car_sku.csv"

# INSERT 列顺序（与 dump 文件一致，36 列）
ALL_COLS = ["id","owner_id","owner_name","spec_id","spec_name","brand_id","brand_name",
    "series_id","series_name","model_id","model_name","outer_color_id","outer_color_name",
    "guide_price","bare_car_price","logistics_cost","interest_cost","other_cost","sku_tag",
    "settlement_price","sale_price","sale_price_finance","intended_landing_price",
    "sale_price_expire_date","is_virtual_source","in_store_insurance","can_issue_vat_invoice",
    "energy_type","finance_manager_name","sale_status","memo","creator_id","creator_name",
    "sku_picture","is_deleted","gmt_create","gmt_modified"]

# 导出到 CSV 的列（保留业务关键字段）
KEEP = ["id","owner_name","spec_name","brand_name","series_name","model_name",
    "outer_color_name","guide_price","bare_car_price","settlement_price","sale_price",
    "sale_price_finance","intended_landing_price","sku_tag","energy_type",
    "is_virtual_source","in_store_insurance","can_issue_vat_invoice","sale_status",
    "memo","sku_picture","is_deleted"]

def split_values(s):
    """按顶层逗号切分 VALUES 元组内容，处理单引号字符串和 \' 转义"""
    vals, cur, in_str, i = [], [], False, 0
    while i < len(s):
        c = s[i]
        if in_str:
            if c == "\\" and i + 1 < len(s):
                cur.append(s[i+1]); i += 2; continue
            if c == "'":
                if i + 1 < len(s) and s[i+1] == "'":   # '' 转义
                    cur.append("'"); i += 2; continue
                in_str = False; i += 1; continue
            cur.append(c); i += 1
        else:
            if c == "'": in_str = True; i += 1
            elif c == ",": vals.append("".join(cur)); cur = []; i += 1
            else: cur.append(c); i += 1
    vals.append("".join(cur))
    # dump 中逗号后有空格，统一 strip；NULL → 空串
    return [v.strip() if v.strip() != "NULL" else "" for v in vals]

rows = []
with open(SRC, encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if not line.upper().startswith("INSERT INTO"):
            continue
        m = re.search(r"VALUES\s*\((.*)\)\s*;\s*$", line, re.IGNORECASE | re.DOTALL)
        if not m:
            print("跳过无法解析的行:", line[:80]); continue
        vals = split_values(m.group(1))
        if len(vals) != len(ALL_COLS):
            print(f"列数不符({len(vals)}): {line[:80]}"); continue
        row = dict(zip(ALL_COLS, vals))
        rows.append(row)

import os
os.makedirs(os.path.dirname(DST), exist_ok=True)
with open(DST, "w", encoding="utf-8", newline="") as f:
    w = csv.DictWriter(f, fieldnames=KEEP)
    w.writeheader()
    for r in rows:
        w.writerow({k: r[k] for k in KEEP})

print(f"解析 {len(rows)} 条 → {DST}")
# 快速统计
from collections import Counter
print("品牌分布:", dict(Counter(r["brand_name"] for r in rows)))
print("上架状态:", dict(Counter(r["sale_status"] for r in rows)))
print("能源类型:", dict(Counter(r["energy_type"] for r in rows)))
#!/usr/bin/env python3
"""schema yml 字段 description 补全。

只补缺失的 description，已有的绝不动；description 不参与索引定义比对，
不会触发索引重建。生成规则：整名覆盖表 > 分词词典合成 > 上报未识别。
用法：enrich-schema-descriptions.py [--apply]（默认 dry-run）
"""
import glob
import os
import re
import sys

import yaml

# 仓库根 = 本脚本所在 scripts/ 的上一级
SCHEMA_GLOB = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                           "deploy", "schema", "ecomm.*.yml")

APPLY = "--apply" in sys.argv

# ---------- 整名覆盖（歧义/特殊字段，优先级最高） ----------
WHOLE: dict[str, str] = {
    "id": "主键",
    "status": "状态码",
    "type": "类型码",
    "sort": "排序值（小在前）",
    "remark": "备注",
    "content": "内容",
    "source": "来源",
    "path": "路径",
    "images": "图片列表",
    "avatar": "头像地址",
    "username": "登录用户名",
    "real_name": "真实姓名",
    "title": "标题",
    "address": "详细地址",
    "email": "邮箱",
    "mobile": "手机号",
    "zip_code": "邮政编码",
    "weight": "重量（kg）",
    "total_weight": "总重量（kg）",
    "width": "宽度（cm）",
    "height": "高度（cm）",
    "capacity": "库容",
    "longitude": "经度",
    "latitude": "纬度",
    "bank_name": "开户银行",
    "compensation": "赔偿金额（元）",
    "is_deleted": "软删除标记（0/1）",
    "is_default": "默认标记（0/1）",
    "limit_per_user": "每人限领数量",
    "convert_rate": "转化率",
    "settle_cycle": "结算周期",
    "official_site": "官方网站",
    "short_name": "简称",
    "company_name": "公司名称",
    "attr_name": "属性名",
    "track_desc": "轨迹描述",
    "score_desc": "描述评分",
    "score_service": "服务评分",
    "score_logistics": "物流评分",
    "stat_date": "统计日期（YYYYMMDD）",
    "period_start": "账期开始日",
    "period_end": "账期结束日",
    "role_code": "角色编码",
    "role_name": "角色名称",
    "fail_reason": "失败原因",
    "handle_result": "处理结果",
    "handle_status": "处理状态码",
    "sign_date": "签订日期",
    "sku_name": "SKU 名称",
    "receiver": "收货人",
    "receiver_mobile": "收货人手机号",
    "receiver_name": "收货人姓名",
    "contact_name": "联系人姓名",
    "contact_phone": "联系电话",
    "contact_mobile": "联系手机号",
    "logistics_no": "物流单号",
    "payment_no": "支付单号",
    "order_no": "订单号",
    "images": "图片列表",
    # —— 第三轮：剩余歧义项整名覆盖 ——
    "after_available": "变动后可用数量",
    "after_qty": "变动后数量",
    "after_sale_days": "售后天数",
    "after_sale_desc": "售后描述",
    "after_sale_id": "售后单 ID",
    "after_sale_no": "售后单号",
    "append_content": "追加内容",
    "bank_account": "银行账号",
    "bank_branch": "开户支行",
    "bank_code": "银行编码",
    "base_ratio": "基础比率",
    "bear_ratio": "承担比率",
    "before_available": "变动前可用数量",
    "before_qty": "变动前数量",
    "brand_story": "品牌故事",
    "description": "描述",
    "face_value": "面值（元）",
    "has_append": "是否有追加（0/1）",
    "has_order": "是否有订单（0/1）",
    "id_card_no": "身份证号",
    "legal_id_card": "法定代表人身份证号",
    "order_source": "订单来源",
    "account_no": "账号",
}

# ---------- 分词词典 ----------
TOK: dict[str, str] = {
    "member": "会员", "merchant": "商户", "mch": "商户", "shop": "店铺",
    "product": "商品", "prd": "商品", "sku": "SKU", "goods": "商品",
    "order": "订单", "ord": "订单", "pay": "支付", "payment": "支付",
    "refund": "退款", "aftersale": "售后", "afc": "售后", "return": "退货",
    "exchange": "换货", "dispute": "争议", "stock": "库存", "stk": "库存",
    "warehouse": "仓库", "coupon": "优惠券", "promotion": "促销",
    "mkt": "营销", "point": "积分", "balance": "余额", "cart": "购物车",
    "region": "地区", "logistics": "物流", "log": "物流", "delivery": "配送",
    "contract": "合同", "settlement": "结算", "settle": "结算",
    "commission": "佣金", "audit": "审核", "operator": "操作人",
    "handler": "处理人", "auditor": "审核人", "user": "用户", "usr": "会员",
    "admin": "管理员", "role": "角色", "permission": "权限", "category": "类目",
    "brand": "品牌", "supplier": "供应商", "gift": "赠品", "seckill": "秒杀",
    "groupbuy": "拼团", "browse": "浏览", "favorite": "收藏", "search": "搜索",
    "notice": "公告", "job": "任务", "ops": "运营", "base": "基础",
    "price": "价格", "stocktake": "盘点", "transfer": "调拨", "purchase": "采购",
    "supply": "供货", "member_level": "会员等级", "level": "等级",
    "discount": "折扣", "freight": "运费", "amount": "金额", "fee": "费用",
    "total": "合计", "actual": "实付", "original": "原价", "cost": "成本",
    "sale": "销售", "diff": "差异", "cash": "现金", "min": "最低",
    "max": "最高", "qty": "数量", "quantity": "数量", "num": "数量",
    "count": "次数", "weight": "重量", "rate": "比率", "start": "开始",
    "end": "结束", "begin": "开始", "last": "最近", "next": "下次",
    "create": "创建", "created": "创建", "update": "更新", "updated": "更新",
    "delete": "删除", "deleted": "删除", "apply": "申请", "handle": "处理",
    "ship": "发货", "receive": "收货", "finish": "完成", "cancel": "取消",
    "confirm": "确认", "auto": "自动", "login": "登录", "expire": "过期",
    "valid": "有效", "used": "已用", "used_point": "使用积分", "used_point_amount": "使用积分",
    "receiver": "收货人", "contact": "联系人", "image": "图片", "pic": "图片",
    "url": "地址", "avatar": "头像", "icon": "图标", "name": "名称",
    "title": "标题", "desc": "描述", "remark": "备注", "reason": "原因",
    "result": "结果", "flag": "标记", "status": "状态", "type": "类型",
    "code": "编码", "no": "单号", "sn": "序列号", "phone": "电话",
    "mobile": "手机号", "email": "邮箱", "address": "地址", "date": "日期",
    "time": "时间", "at": "时间", "day": "日", "version": "版本",
    "tenant": "租户", "parent": "父级", "children": "子级", "score": "评分",
    "summary": "摘要", "label": "标签", "tag": "标签", "keyword": "关键词",
    "config": "配置", "param": "参数", "value": "值", "key": "键",
    "channel": "渠道", "trade": "交易", "invoice": "发票", "tax": "税费",
    "buyer": "买家", "seller": "卖家", "manual": "人工", "system": "系统",
    "snapshot": "快照", "checkout": "结算", "auth": "认证", "login_log": "登录日志",
    "template": "模板", "banner": "横幅", "page": "页面", "site": "站点",
    "area": "区域", "city": "城市", "province": "省份", "district": "区县",
    "street": "街道", "tag_id": "标签", "warn": "预警", "warning": "预警",
    "lock": "锁定", "flow": "流水", "adjust": "调整", "check": "核查",
    "daily": "每日", "stat": "统计", "report": "报表", "rpt": "报表",
    "msg": "消息", "message": "消息", "send": "发送", "read": "阅读",
    # —— 词典扩充（覆盖此前未识别词）——
    "account": "账户", "action": "操作", "activity": "活动", "add": "新增",
    "available": "可用", "sign": "签订", "rank": "排名", "qual": "资质",
    "perm": "权限点", "notify": "通知", "delta": "变动量", "company": "公司",
    "success": "成功", "sold": "已售", "risk": "风险", "process": "流程",
    "new": "新增", "join": "加入", "ip": "IP 地址", "growth": "增长",
    "frozen": "冻结", "free": "免费", "first": "首次", "expect": "预期",
    "bill": "账单", "append": "追加", "answer": "回复", "withdraw": "提现",
    "way": "方式", "unit": "单位", "track": "轨迹", "team": "团队",
    "sub": "子级", "spec": "规格", "shelf": "上架", "service": "服务",
    "rule": "规则", "register": "注册", "recon": "对账", "person": "人",
    "package": "包裹", "node": "节点", "local": "本地", "limit": "限制",
    "legal": "法定代表人", "item": "条目", "income": "收入", "hours": "小时",
    "good": "好评", "dict": "字典", "default": "默认", "current": "当前",
    "comment": "评论", "card": "卡", "biz": "业务", "bear": "承担",
    "avg": "平均", "activity": "活动", "volume": "销量", "visitor": "访客",
    "visit": "访问", "view": "浏览量", "verified": "已认证", "use": "使用",
    "transit": "运输中", "threshold": "阈值", "target": "目标",
    "surcharge": "附加费", "support": "支持", "submit": "提交", "stay": "驻留",
    "staff": "员工", "stackable": "可叠加", "size": "尺寸", "show": "展示",
    "shipping": "运费", "session": "会话", "seconds": "秒", "scope": "范围",
    "safety": "保障", "reward": "奖励", "request": "请求", "reply": "答复",
    "release": "发布", "ref": "参考", "reduce": "满减", "recommend": "推荐",
    "received": "已收", "real": "实名", "range": "区间", "question": "问题",
    "query": "查询", "qq": "QQ 号", "publisher": "发布者", "public": "公开",
    "profit": "利润", "priority": "优先级", "primary": "主", "position": "职位",
    "period": "周期", "pending": "待处理", "payload": "载荷", "payable": "应付",
    "password": "密码", "participant": "参与人", "packing": "打包",
    "out": "出库", "org": "组织", "open": "开放", "offline": "线下",
    "occupation": "职业", "obtain": "获得", "nickname": "昵称", "net": "净",
    "need": "需求", "names": "名称", "mode": "模式", "mid": "中期",
    "marriage": "婚姻状况", "market": "市场", "main": "主营", "loss": "亏损",
    "logo": "标识", "locked": "锁定", "lng": "经度", "list": "列表",
    "like": "点赞", "license": "证照", "letter": "字母", "length": "长度",
    "leader": "负责人", "lat": "纬度", "keywords": "关键词", "issued": "签发",
    "invalid": "失效", "interest": "利息", "input": "录入", "inbound": "入库",
    "identity": "身份", "identifier": "标识", "hit": "命中", "hash": "哈希",
    "group": "分组", "gender": "性别", "full": "满额", "from": "来自",
    "file": "文件", "exception": "异常", "enter": "录入", "education": "学历",
    "device": "设备", "deposit": "保证金", "data": "数据", "damaged": "破损",
    "customer": "客户", "credential": "凭证", "continuous": "连续",
    "consume": "消耗", "collect": "收藏", "codes": "编码列表", "client": "客户端",
    "click": "点击", "checked": "已核对", "charge": "收费", "buy": "购买",
    "business": "业务", "branch": "支行", "book": "预约", "birthday": "生日",
    "beneficiary": "受益人", "batch": "批次", "barcode": "条码", "bad": "差评",
    "attachment": "附件", "arrive": "到达", "applicant": "申请人",
    "anonymous": "匿名", "active": "有效", "abandon": "弃单", "attr": "属性",
    "days": "天数", "is": "是否",
}

DROP = {"to", "in", "by", "en", "of", "and", "or"}

MONEY_HINT = {"amount", "fee", "price", "cash", "compensation"}


def compose(name: str, ftype: str, related: str | None) -> tuple[str, list[str]]:
    """返回 (描述, 未识别 token)。"""
    if name in WHOLE:
        base = WHOLE[name]
        if related and "外键" not in base:
            base += f"，外键关联 {related}"
        return base, []

    tokens = name.split("_")
    unknown: list[str] = []
    words: list[str] = []
    for t in tokens:
        if t in TOK:
            words.append(TOK[t])
        elif t in DROP:
            continue
        elif t == "id" and t == tokens[-1]:
            words.append("ID")
        elif t == "ids" and t == tokens[-1]:
            words.append("ID 列表")
        else:
            unknown.append(t)

    if unknown:
        return "", unknown

    desc = "".join(words)

    # 类型/语义后缀微调
    last = tokens[-1]
    if last in ("at", "time", "date") and ftype in ("LONG", "INT"):
        if last == "date":
            pass  # stat_date 等整名覆盖；date 单独出现按日期
        else:
            desc += "（毫秒时间戳）"
    elif name.endswith("_no") or name.endswith("_sn"):
        desc = desc.replace("单号", "单号", 1)
    elif last in MONEY_HINT or (name.endswith("_amount") or name.endswith("_fee")
                                or name.endswith("_price")):
        if ftype in ("DOUBLE",):
            desc += "（元）"
    if related and "外键" not in desc and (name.endswith("_id") or name.endswith("_code")):
        desc += f"，外键关联 {related}"
    return desc, []


def main() -> None:
    missing: dict[str, tuple[str, str, str | None]] = {}   # field -> (file, type, related)
    files = sorted(glob.glob(SCHEMA_GLOB))
    for p in files:
        d = yaml.safe_load(open(p, encoding="utf-8"))
        for f in d["fields"]:
            if not f.get("description"):
                missing.setdefault(f["name"], (p, f["type"], f.get("relatedEntity")))

    resolved: dict[str, str] = {}
    unresolved: dict[str, list[str]] = {}
    for name, (_p, ftype, related) in missing.items():
        desc, unknown = compose(name, ftype, related)
        if desc and not unknown:
            resolved[name] = desc
        elif unknown:
            unresolved[name] = unknown

    print(f"缺描述字段 {len(missing)} 个（唯一名）；可自动生成 {len(resolved)}，未识别 {len(unresolved)}")
    if unresolved:
        print("== 未识别（需补词典/整名覆盖）==")
        for n, toks in sorted(unresolved.items()):
            print(f"  {n}: {toks}")
    if not APPLY:
        print("== 样例 20 条 ==")
        for n in sorted(resolved)[:20]:
            print(f"  {n} = {resolved[n]}")
        print("（dry-run 未写盘，加 --apply 生效）")
        return

    patched = 0
    for p in files:
        d = yaml.safe_load(open(p, encoding="utf-8"))
        changed = False
        for f in d["fields"]:
            if not f.get("description") and f["name"] in resolved:
                f["description"] = resolved[f["name"]]
                changed = True
        if changed:
            write_yml(p, d)
            patched += 1
    print(f"patched {patched} files")


def write_yml(path: str, doc: dict) -> None:
    lines = [f"namespace: {doc['namespace']}", f"entity: {doc['entity']}"]
    if doc.get("description"):
        lines.append(f"description: {doc['description']}")
    lines.append("primaryKeys:")
    for pk in doc["primaryKeys"]:
        lines.append(f"  - {pk}")
    lines.append("fields:")
    for f in doc["fields"]:
        lines.append(f"  - name: {f['name']}")
        lines.append(f"    type: {f['type']}")
        if f.get("indexed"):
            lines.append("    indexed: true")
        if f.get("index"):
            lines.append(f"    index: {f['index']}")
        if f.get("relatedEntity"):
            lines.append(f"    relatedEntity: {f['relatedEntity']}")
        if f.get("tags"):
            lines.append("    tags:")
            for t in f["tags"]:
                lines.append(f"      - {t}")
        if f.get("description"):
            lines.append(f"    description: {f['description']}")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()

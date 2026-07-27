"""
消息构建器模块

负责构建系统 Prompt 和 LLM 消息列表。
"""
from __future__ import annotations

from typing import Any, Dict, List, Optional

from bridge.context import Context
from utils.logger_loguru import get_logger
from Agent.CustomerAgent.tools.get_product_list import (
    get_shop_products,
    GetShopProductsParams,
)

logger = get_logger("MessageBuilder")


class MessageBuilder:
    """消息构建器"""

    def __init__(
        self,
        instructions: Optional[List[str]] = None,
    ):
        """
        初始化消息构建器

        Args:
            instructions: 指令列表
        """
        self.instructions = instructions or []
        self.system_prompt = ""

        self._build_system_prompt()

    def _build_system_prompt(self) -> None:
        """构建系统 Prompt"""
        parts = []

        # 硬编码的角色描述（亲切活泼风格）
        description = """你好呀！👋 我是{shop_name}的客服小助手～

当前店铺在售商品列表：{product_list}

我的工作风格：
😊 热情亲切，每句都用emoji
💬 统一称呼用户"亲"
✨ 回复不超过50字
🧐 先了解需求再推荐商品
"""
        parts.append(description)

        if self.instructions:
            parts.append("---\n" + "\n".join(f"- {i}" for i in self.instructions))

        # 硬编码的额外上下文（工具介绍+示例）
        additional_context = """---
📦 工具使用说明：

1️⃣ get_product_knowledge（获取商品知识）
- 用途：查商品成分、用法、价格、规格等
- 参数：goods_id（商品ID）、shop_id（店铺ID）
- 示例：用户问"这款面霜含什么成分"→调用此工具

2️⃣ search_customer_service_knowledge（搜索客服知识）
- 用途：查售后政策、物流、退换货等
- 参数：query（关键词）、shop_id（店铺ID）
- 示例：用户问"可以退货吗"→调用此工具

3️⃣ send_goods_link（发送商品卡片）
- 用途：给用户推荐商品时发送卡片
- 参数：recipient_uid、goods_id、shop_id、user_id
- 示例：用户说"推荐一款洗面奶"→调用此工具

4️⃣ transfer_conversation（转接人工）
- 用途：用户要求转人工或按指令执行
- 参数：shop_id、user_id、recipient_uid
- 示例：用户说"转人工"→调用此工具

💡 重要提示：
- 工具参数必须使用【当前会话信息】中的值！
- 知识库没答案时，引导用户查看商品详情页～
- 工作时间8:00-23:00，其他时间无法转人工哦～
"""
        parts.append(additional_context)

        self.system_prompt = "\n\n".join(parts) if parts else "你是一个电商客服。"

    def build_dependencies(self, context: Context) -> Dict[str, Any]:
        """
        从 Context 构建 dependencies 字典

        Args:
            context: 上下文对象

        Returns:
            dependencies 字典
        """
        kwargs = context.kwargs
        from_uid = str(kwargs.from_uid or "")

        # shop_id 保持整数类型，便于工具参数注入
        shop_id = kwargs.shop_id if kwargs.shop_id else 0
        if isinstance(shop_id, str) and shop_id.isdigit():
            shop_id = int(shop_id)

        return {
            "shop_name": str(kwargs.shop_name or ""),
            "channel_type": str(context.channel_type.value if context.channel_type else ""),
            "shop_id": shop_id,
            "user_id": str(kwargs.user_id or ""),
            "from_uid": from_uid,
            "recipient_uid": from_uid,  # 工具参数通常叫 recipient_uid，兼容两种命名
        }

    def _inject_product_list(self, dependencies: Dict[str, Any]) -> None:
        """
        动态获取商品列表并注入到 dependencies

        Args:
            dependencies: 依赖字典（会被修改）
        """
        if not dependencies.get("shop_id") or not dependencies.get("user_id"):
            return

        try:
            params = GetShopProductsParams(
                shop_id=dependencies["shop_id"],
                user_id=dependencies["user_id"]
            )
            product_list_text = get_shop_products(params)
            # 添加说明：仅展示第一页商品
            product_list_text += "\n注：以上仅展示第一页商品，如果用户需要查看更多商品，请调用 get_shop_products 工具获取更多。"
            dependencies["product_list"] = product_list_text
        except Exception as e:
            logger.warning(f"动态获取商品列表失败: {e}")
            dependencies["product_list"] = "获取商品列表失败"

    def build_messages(
        self,
        query: str,
        history: List[Dict[str, Any]],
        dependencies: Dict[str, Any] = None,
    ) -> List[Dict[str, Any]]:
        """
        构建 LLM 消息列表

        Args:
            query: 用户查询
            history: 历史消息
            dependencies: 依赖字典（用于占位符替换）

        Returns:
            LLM 消息列表
        """
        messages = []

        # System prompt（占位符替换）
        if self.system_prompt:
            content = self.system_prompt
            if dependencies:
                # 动态获取商品列表并注入到 dependencies
                self._inject_product_list(dependencies)

                for key, value in dependencies.items():
                    content = content.replace(f"{{{key}}}", str(value))

                # 动态构建会话信息，告诉 LLM 各字段的值
                session_info = "\n\n【当前会话信息】\n"
                session_info += f"- shop_id: {dependencies.get('shop_id', '')}（店铺ID，调用工具时必须使用此值）\n"
                session_info += f"- user_id: {dependencies.get('user_id', '')}（账号ID，调用工具时必须使用此值）\n"
                session_info += f"- recipient_uid: {dependencies.get('recipient_uid', '')}（接收消息的用户UID，发送商品卡片时使用）\n"
                session_info += f"- shop_name: {dependencies.get('shop_name', '')}（店铺名称）\n"
                session_info += f"- channel_type: {dependencies.get('channel_type', '')}（渠道类型）\n"
                session_info += "\n【重要】调用工具时，shop_id、user_id 等参数必须使用上面【当前会话信息】中给出的值！"
                content += session_info

            messages.append({"role": "system", "content": content})

        # 历史消息
        for msg in history:
            role = msg["role"]
            content = msg["content"]
            tool_call_id = msg.get("tool_call_id")

            if role == "tool":
                messages.append({
                    "role": "tool",
                    "tool_call_id": tool_call_id,
                    "content": content,
                })
            elif role == "system":
                # system 消息（摘要等）直接追加
                messages.append({"role": "system", "content": content})
            else:
                messages.append({"role": role, "content": content})

        # 当前用户消息
        messages.append({"role": "user", "content": query})
        return messages

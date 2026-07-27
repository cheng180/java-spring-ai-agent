"""
发送商品卡片工具

使用自定义 @agent_tool 装饰器，无需 Agno 依赖。
"""
from typing import Optional, Union
from pydantic import BaseModel, Field

from Agent.CustomerAgent.custom.tool_decorator import agent_tool
from Channel.pinduoduo.utils.API.send_message import SendMessage
from utils.logger_loguru import get_logger

logger = get_logger("SendGoodsLinkTool")


class SendGoodsLinkParams(BaseModel):
    """发送商品卡片参数"""
    recipient_uid: Optional[str] = Field(default=None, description="接收消息的用户UID")
    goods_id: Optional[int] = Field(default=None, description="商品ID。必须使用商品列表中给出的 商品ID（通常是很大的数字）。绝对不能使用列表序号(1, 2, 3...)，那是错误的！")
    shop_id: Optional[Union[str, int]] = Field(default=None, description="店铺ID")
    user_id: Optional[Union[str, int]] = Field(default=None, description="用户ID（账号ID）")


@agent_tool(
    name="send_goods_link",
    description="向用户发送商品卡片链接，用于客服主动推荐商品。重要：goods_id 必须使用商品列表中给出的真实商品ID（大数），严禁使用列表序号（1、2、3这样的小数）！",
    param_model=SendGoodsLinkParams,
)
def send_goods_link(params: SendGoodsLinkParams) -> str:
    """
    向用户发送商品卡片链接。

    Args:
        params: SendGoodsLinkParams，包含 recipient_uid, goods_id, shop_id, user_id

    Returns:
        str: 发送结果，成功返回 True，失败返回错误信息
    """
    try:
        if not all([params.recipient_uid, params.goods_id, params.shop_id, params.user_id]):
            logger.error(f"商品卡片发送失败: 缺少必要参数 (recipient_uid={params.recipient_uid}, goods_id={params.goods_id}, shop_id={params.shop_id}, user_id={params.user_id})")
            return f"发送失败：缺少必要的参数 (recipient_uid={params.recipient_uid}, goods_id={params.goods_id}, shop_id={params.shop_id}, user_id={params.user_id})"

        # 防护：真实商品ID都是大数，如果goods_id很小，很可能是把列表序号误当作商品ID了
        if params.goods_id is not None and params.goods_id < 1000:
            logger.warning(f"商品ID可能错误: goods_id={params.goods_id} 太小，大概率是列表序号不是真实商品ID，请重新从商品列表中选择正确的商品ID")
            return f"发送失败：goods_id={params.goods_id} 无效。你错误地使用了列表序号作为商品ID，请回到商品列表中，使用'商品ID'标签后面给出的那个大数字作为goods_id，重新调用工具。"

        sender = SendMessage(str(params.shop_id), str(params.user_id))
        result = sender.send_mallGoodsCard(params.recipient_uid, params.goods_id, biz_type=2)

        if result and result.get("success"):
            logger.info(f"商品卡片发送成功: goods_id={params.goods_id}, recipient_uid={params.recipient_uid}, shop_id={params.shop_id}")
            return "商品卡片发送成功"
        else:
            error_msg = result.get('error_msg', '发送失败') if result else '发送失败'
            logger.error(f"商品卡片发送失败: {error_msg}, goods_id={params.goods_id}, recipient_uid={params.recipient_uid}")
            return f"商品卡片发送失败: {error_msg}"

    except Exception as e:
        logger.error(f"发送商品卡片异常: {str(e)}, goods_id={params.goods_id}, recipient_uid={params.recipient_uid}")
        return f"发送商品卡片异常: {str(e)}"

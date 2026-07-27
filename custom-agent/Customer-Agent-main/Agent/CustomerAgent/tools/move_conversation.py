"""
会话转接工具

将当前会话转接给人工客服。
"""
from typing import Optional, Union
from pydantic import BaseModel, Field

from Agent.CustomerAgent.custom.tool_decorator import agent_tool
from Channel.pinduoduo.utils.API.send_message import SendMessage
from utils.logger_loguru import get_logger

logger = get_logger("TransferConversationTool")


class TransferConversationParams(BaseModel):
    """会话转接参数"""
    shop_id: Optional[Union[str, int]] = Field(default=None, description="店铺ID")
    user_id: Optional[Union[str, int]] = Field(default=None, description="用户ID（账号ID）")
    recipient_uid: Optional[str] = Field(default=None, description="接收转接的用户UID")


@agent_tool(
    name="transfer_conversation",
    description="将当前会话转接给人工客服。",
    param_model=TransferConversationParams,
)
def transfer_conversation(params: TransferConversationParams) -> str:
    """
    将当前会话转接给人工客服。
    """
    try:
        if not all([params.shop_id, params.user_id, params.recipient_uid]):
            return f"转接失败：缺少必要的会话信息 (shop_id={params.shop_id}, user_id={params.user_id}, recipient_uid={params.recipient_uid})"

        sender = SendMessage(str(params.shop_id), str(params.user_id))
        cs_list = sender.getAssignCsList()
        my_cs_uid = f"cs_{params.shop_id}_{params.user_id}"
        if cs_list and isinstance(cs_list, dict):
            # 过滤掉自己，不转接给自己
            available_cs_uids = [uid for uid in cs_list.keys() if uid != my_cs_uid]

            if available_cs_uids:
                # 选择第一个可用的客服
                cs_uid = available_cs_uids[0]
                # 转移会话
                transfer_result = sender.move_conversation(params.recipient_uid, cs_uid)

                if transfer_result and transfer_result.get('success'):
                    logger.info(f"会话转接成功: recipient_uid={params.recipient_uid}, to_cs_uid={cs_uid}")
                    return "会话转接成功"
                else:
                    logger.warning(f"会话转接失败: transfer_result={transfer_result}")
                    return "会话转接失败"
            else:
                logger.warning(f"会话转接失败: 当前无可用的人工客服 (shop_id={params.shop_id})")
                return "当前无可用的人工客服"
        logger.warning("会话转接失败：无法获取客服列表")
        return "会话转接失败：无法获取客服列表"

    except Exception as e:
        logger.error(f"转接过程中发生错误: {str(e)}")
        return f"转接过程中发生错误: {str(e)}"

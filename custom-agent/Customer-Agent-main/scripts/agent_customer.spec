# -*- mode: python ; coding: utf-8 -*-
"""
PyInstaller spec for Agent-Customer
生成命令: pyinstaller scripts/agent_customer.spec
"""

import sys
import os
from pathlib import Path

block_cipher = None

# 项目根目录（spec 文件位于 scripts/ 目录，上两级为项目根目录）
if '__file__' in globals():
    _spec_dir = Path(os.path.abspath(__file__)).parent
    PROJECT_ROOT = _spec_dir.parent
elif len(sys.argv) > 0:
    # fallback: 从命令行参数推导
    PROJECT_ROOT = Path(sys.argv[0]).resolve().parent.parent
else:
    # last fallback: 从 cwd 推导
    PROJECT_ROOT = Path.cwd()

# ================================
# 基础配置
# ================================
a = Analysis(
    [str(PROJECT_ROOT / "app.py")],
    pathex=[str(PROJECT_ROOT)],
    binaries=[],
    datas=[
        # 图标文件
        (str(PROJECT_ROOT / "icon" / "icon.ico"), "icon"),
        # 配置文件（如果存在）
    ],
    hiddenimports=[
        # === PyQt6 & Fluent Widgets ===
        "PyQt6",
        "PyQt6.QtCore",
        "PyQt6.QtGui",
        "PyQt6.QtWidgets",
        "PyQt6.sip",
        "qfluentwidgets",
        "qfluentwidgets.common",
        "qfluentwidgets.components",
        "qfluentwidgets.navigation",
        "qfluentwidgets.window",
        # === AI / Agent ===
        "agno",
        "agno.agent",
        "agno.models",
        "agno.models.openai",
        "agno.knowledge",
        "agno.knowledge.embedder",
        "agno.db",
        "agno.db.sqlite",
        "agno.tools",
        "agno.team",
        "agno.run",
        "agno.team.agent",
        # === 嵌入器 ===
        "openai",
        "openai._base",
        "openai._models",
        "openai._client",
        "tiktoken",
        "tiktoken_ext",
        "tiktoken_ext.openai_public",
        # === 数据库 ===
        "sqlalchemy",
        "sqlalchemy.orm",
        "sqlalchemy.sql",
        "sqlalchemy.dialects.sqlite",
        "lancedb",
        "lancedb.embeddings",
        "lancedb.vector",
        # === 搜索 ===
        "tantivy",
        # === 日志 ===
        "loguru",
        "loguru._logger",
        # === Web / 网络 ===
        "websockets",
        "websockets.client",
        "websockets.server",
        "aiohttp",
        "aiohttp.client",
        "aiohttp.web",
        "aiohttp.websocket",
        "requests",
        "urllib3",
        "charset_normalizer",
        # === 浏览器自动化 ===
        "playwright",
        "playwright._impl",
        "playwright.async_api",
        # === 数据处理 ===
        "pandas",
        "pandas._libs",
        "numpy",
        "numpy.core",
        "numpy._core",
        "openpyxl",
        "openpyxl.cell",
        "openpyxl.workbook",
        "pypdf",
        "docx",
        "docx.document",
        "docx.oxml",
        # === 图像 ===
        "PIL",
        "PIL._imaging",
        "cv2",
        "cv2.cv2",
        # === 异步 ===
        "asyncio",
        "aiofiles",
        # === 工具类 ===
        "pydantic",
        "pydantic.base",
        "pydantic.fields",
        "pydantic.dataclasses",
        "pydantic.v1",
        "pydantic.v1.error_messages",
        "pydantic.v1.fields",
        "volcengine",
        "volcengine.base",
        "volcengine.viking_knowledgebase",
        "volcengine.viking_knowledgebase.Collection",
        "volcengine.viking_knowledgebase.Doc",
        # === 项目内部模块 ===
        "config",
        "core.di_container",
        "core.connection_status",
        "core.cache",
        "core.base_service",
        "database.db_manager",
        "database.models",
        "database.connection_pool",
        "bridge.context",
        "bridge.reply",
        "Message.message",
        "Message.core.queue",
        "Message.core.consumer",
        "Message.core.handlers",
        "Message.handlers.base",
        "Message.handlers.ai_handler",
        "Message.handlers.keyword_handler",
        "Message.handlers.preprocessor",
        "Agent.bot",
        "Agent.CustomerAgent.custom.customer_agent",
        "Agent.CustomerAgent.custom.tool_decorator",
        "Agent.CustomerAgent.custom.session_manager",
        "Agent.CustomerAgent.tools",
        "Channel.channel",
        "Channel.pinduoduo.pdd_channel",
        "Channel.pinduoduo.pdd_message",
        "Channel.pinduoduo.pdd_login",
        "Channel.pinduoduo.utils.base_request",
        "Channel.pinduoduo.utils.API.get_token",
        "Channel.pinduoduo.utils.API.send_message",
        "Channel.pinduoduo.utils.API.get_shop_info",
        "Channel.pinduoduo.utils.API.Set_up_online",
        "Channel.pinduoduo.utils.API.product_manager",
        "Channel.pinduoduo.utils.API.get_user_info",
        "utils.logger_loguru",
        "utils.logger_config",
        "utils.resource_manager",
        "utils.path_utils",
        "utils.runtime_path",
        # === 避免 pydantic v1/v2 冲突 ===
        "pydantic.errors",
        "pydantic.fields",
        "pydantic.main",
        "pydantic.schema",
        "pydantic.types",
        "pydantic.validators",
        "pydantic.class_validators",
        "pydantic.config",
        "pydantic.parse",
        "pydantic.tools",
        "pydantic.utils",
        "pydantic.validators",
        # === rich (for volcengine) ===
        "rich",
        "rich.console",
        "rich.table",
        "rich.progress",
        # === 避免 importlib 静默失败 ===
        "importlib",
        "importlib.util",
        "importlib.abc",
        # === jsonschema (for openapi) ===
        "jsonschema",
        "jsonschema_specifications",
        # === certifi / charset ===
        "certifi",
        "charset_normalizer",
        # === httpx (for openai) ===
        "httpx",
        "httpcore",
    ],
    hookspath=[],
    hooksconfig={},
    keys=block_cipher,
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,          # Windows 下关闭 UPX（兼容性更好）
    console=False,        # 不显示控制台窗口（GUI 程序）
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)

# ================================
# 生成 PYZ（Python 库）
# ================================
pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

# ================================
# 生成 EXE
# ================================
exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="AgentCustomer",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=str(PROJECT_ROOT / "icon" / "icon.ico"),
    # Windows 特定
    version="",
    description="电商AI客服助手",
    product_name="Agent-Customer",
    product_version="0.1.0",
    company_name="",
    legal_copyright="",
    # 权限
   RequestedExecutionLevel="asInvoker",
    # 环境变量：解决 uv 管理 Python 在 frozen 模式下找不到 Python 运行时的问题
    env=[
        ("PYTHONHOME", str(Path(sys.exec_prefix))),
    ],
)

# ================================
# 收集所有文件到 dist 目录
# ================================
coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name="AgentCustomer",
)

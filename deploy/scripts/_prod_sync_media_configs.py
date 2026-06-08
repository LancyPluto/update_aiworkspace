#!/usr/bin/env python3
from __future__ import annotations

import os
import json
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

import paramiko

HOST = os.environ.get("DEPLOY_HOST", "8.134.93.203")
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")
ROOT = Path(__file__).resolve().parents[2]
BUNDLE_PATH = ROOT / "ai-tool-market-config-2026-06-08.json"


@dataclass(frozen=True)
class VendorAccount:
    vendor_code: str
    account_name: str
    base_url: str
    api_key: str
    extra_auth_json: str
    console_url: str
    balance_url: str
    balance_query_mode: str
    balance_amount: str | None
    balance_currency: str
    enabled: int


@dataclass(frozen=True)
class ModelConfig:
    display_name: str
    config_code: str
    provider: str
    model_name: str
    base_url: str | None
    minimax_group_id: str | None
    console_url: str | None
    balance_url: str | None
    docs_url: str | None
    timeout_seconds: int
    input_token_price_per_1k: str
    output_token_price_per_1k: str
    input_token_price_per_1m: str
    output_token_price_per_1m: str
    billing_unit: str
    unit_price: str
    enabled: int
    agent_enabled: int
    is_default: int
    capabilities: str | None
    extra_auth_json: str | None


@dataclass(frozen=True)
class ToolConfig:
    tool_code: str
    tool_name: str
    category_code: str
    description: str | None
    cover_url: str | None
    tool_type: str
    input_modality: str
    output_modality: str
    config_note: str | None
    status: str
    estimated_credit_cost: int
    model_config_code: str
    execution_handler: str | None


def local_query(sql: str) -> list[list[str | None]]:
    cmd = [
        "docker",
        "compose",
        "-f",
        "deploy/docker-compose.yml",
        "exec",
        "-T",
        "mysql",
        "mysql",
        "-uroot",
        "-proot123456",
        "--batch",
        "--raw",
        "--skip-column-names",
        "ai_supermarket_v1",
        "-e",
        sql,
    ]
    result = subprocess.run(cmd, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    rows: list[list[str | None]] = []
    for line in result.stdout.splitlines():
        rows.append([None if value == r"\N" else value for value in line.split("\t")])
    return rows


def one(sql: str) -> list[str | None]:
    rows = local_query(sql)
    if not rows:
        raise RuntimeError(f"local query returned no rows: {sql[:120]}")
    return rows[0]


def sql_string(value: str | None) -> str:
    if value is None:
        return "NULL"
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def sql_number(value: str | int | None) -> str:
    if value is None or value == "":
        return "NULL"
    return str(value)


def load_vendor(vendor_code: str, base_url: str) -> VendorAccount:
    row = one(
        "SELECT vendor_code,account_name,base_url,api_key,COALESCE(extra_auth_json,''),"
        "COALESCE(console_url,''),COALESCE(balance_url,''),balance_query_mode,"
        "IFNULL(CAST(balance_amount AS CHAR),'NULL'),COALESCE(balance_currency,'CNY'),enabled "
        "FROM model_vendor_accounts "
        f"WHERE is_deleted=0 AND vendor_code={sql_string(vendor_code)} AND base_url={sql_string(base_url)} "
        "AND CHAR_LENGTH(COALESCE(api_key,'')) > 0 "
        "ORDER BY enabled DESC, health_status='OK' DESC, id DESC LIMIT 1"
    )
    return VendorAccount(
        vendor_code=row[0] or "",
        account_name=row[1] or "",
        base_url=row[2] or "",
        api_key=row[3] or "",
        extra_auth_json=row[4] or "",
        console_url=row[5] or "",
        balance_url=row[6] or "",
        balance_query_mode=row[7] or "MANUAL",
        balance_amount=None if row[8] == "NULL" else row[8],
        balance_currency=row[9] or "CNY",
        enabled=int(row[10] or "1"),
    )


def load_model(config_code: str) -> ModelConfig:
    row = one(
        "SELECT display_name,config_code,provider,model_name,base_url,minimax_group_id,console_url,balance_url,docs_url,"
        "timeout_seconds,input_token_price_per_1k,output_token_price_per_1k,input_token_price_per_1m,output_token_price_per_1m,"
        "billing_unit,unit_price,enabled,agent_enabled,is_default,capabilities,extra_auth_json "
        "FROM agent_model_configs "
        f"WHERE is_deleted=0 AND config_code={sql_string(config_code)} LIMIT 1"
    )
    return ModelConfig(
        display_name=row[0] or "",
        config_code=row[1] or "",
        provider=row[2] or "",
        model_name=row[3] or "",
        base_url=row[4],
        minimax_group_id=row[5],
        console_url=row[6],
        balance_url=row[7],
        docs_url=row[8],
        timeout_seconds=int(row[9] or "60"),
        input_token_price_per_1k=row[10] or "0",
        output_token_price_per_1k=row[11] or "0",
        input_token_price_per_1m=row[12] or "0",
        output_token_price_per_1m=row[13] or "0",
        billing_unit=row[14] or "TOKEN_PER_M",
        unit_price=row[15] or "0",
        enabled=int(row[16] or "1"),
        agent_enabled=int(row[17] or "1"),
        is_default=int(row[18] or "0"),
        capabilities=row[19],
        extra_auth_json=row[20],
    )


def load_tool(tool_code: str) -> ToolConfig:
    row = one(
        "SELECT t.tool_code,t.tool_name,c.category_code,t.description,t.cover_url,t.tool_type,t.input_modality,"
        "t.output_modality,t.config_note,t.status,t.estimated_credit_cost,m.config_code,t.execution_handler "
        "FROM ai_tools t "
        "JOIN tool_categories c ON c.id=t.category_id "
        "LEFT JOIN agent_model_configs m ON m.id=t.model_config_id "
        f"WHERE t.is_deleted=0 AND t.tool_code={sql_string(tool_code)} LIMIT 1"
    )
    return ToolConfig(
        tool_code=row[0] or "",
        tool_name=row[1] or "",
        category_code=row[2] or "",
        description=row[3],
        cover_url=row[4],
        tool_type=row[5] or "TEXT_GENERATION",
        input_modality=row[6] or "TEXT",
        output_modality=row[7] or "TEXT",
        config_note=row[8],
        status=row[9] or "DRAFT",
        estimated_credit_cost=int(row[10] or "0"),
        model_config_code=row[11] or "",
        execution_handler=row[12],
    )


def load_bundle() -> dict:
    with BUNDLE_PATH.open("r", encoding="utf-8") as bundle_file:
        return json.load(bundle_file)


def bundle_vendor(bundle: dict, vendor_code: str, base_url: str) -> VendorAccount:
    for item in bundle.get("vendorAccounts") or []:
        if item.get("vendorCode") == vendor_code and item.get("baseUrl") == base_url and item.get("apiKey"):
            return VendorAccount(
                vendor_code=item.get("vendorCode") or "",
                account_name=item.get("accountName") or "",
                base_url=item.get("baseUrl") or "",
                api_key=item.get("apiKey") or "",
                extra_auth_json=item.get("extraAuthJson") or "",
                console_url=item.get("consoleUrl") or "",
                balance_url=item.get("balanceUrl") or "",
                balance_query_mode=item.get("balanceQueryMode") or "MANUAL",
                balance_amount=None if item.get("balanceAmount") is None else str(item.get("balanceAmount")),
                balance_currency=item.get("balanceCurrency") or "CNY",
                enabled=1 if item.get("enabled", True) else 0,
            )
    raise RuntimeError(f"vendor account not found in bundle: {vendor_code} {base_url}")


def bundle_model(bundle: dict, config_code: str) -> ModelConfig:
    for item in bundle.get("modelConfigs") or []:
        if item.get("configCode") == config_code:
            return ModelConfig(
                display_name=item.get("displayName") or "",
                config_code=item.get("configCode") or "",
                provider=item.get("provider") or "",
                model_name=item.get("modelName") or "",
                base_url=item.get("baseUrl"),
                minimax_group_id=item.get("minimaxGroupId"),
                console_url=item.get("consoleUrl"),
                balance_url=item.get("balanceUrl"),
                docs_url=item.get("docsUrl"),
                timeout_seconds=int(item.get("timeoutSeconds") or 60),
                input_token_price_per_1k=str(item.get("inputTokenPricePer1k") or 0),
                output_token_price_per_1k=str(item.get("outputTokenPricePer1k") or 0),
                input_token_price_per_1m=str(item.get("inputTokenPricePer1m") or 0),
                output_token_price_per_1m=str(item.get("outputTokenPricePer1m") or 0),
                billing_unit=item.get("billingUnit") or "TOKEN_PER_M",
                unit_price=str(item.get("unitPrice") or 0),
                enabled=1 if item.get("enabled", True) else 0,
                agent_enabled=1 if item.get("agentEnabled", True) else 0,
                is_default=1 if item.get("isDefault", False) else 0,
                capabilities=json.dumps(item.get("capabilities") or [], ensure_ascii=False),
                extra_auth_json=item.get("extraAuthJson") or None,
            )
    raise RuntimeError(f"model config not found in bundle: {config_code}")


def bundle_tool(bundle: dict, tool_code: str) -> ToolConfig:
    for item in bundle.get("tools") or []:
        if item.get("toolCode") == tool_code:
            return ToolConfig(
                tool_code=item.get("toolCode") or "",
                tool_name=item.get("toolName") or "",
                category_code=item.get("categoryCode") or "",
                description=item.get("description"),
                cover_url=item.get("coverUrl"),
                tool_type=item.get("toolType") or "TEXT_GENERATION",
                input_modality=item.get("inputModality") or "TEXT",
                output_modality=item.get("outputModality") or "TEXT",
                config_note=item.get("configNote"),
                status=item.get("status") or "DRAFT",
                estimated_credit_cost=int(item.get("estimatedCreditCost") or 0),
                model_config_code=item.get("modelConfigCode") or "",
                execution_handler=item.get("executionHandler"),
            )
    raise RuntimeError(f"tool not found in bundle: {tool_code}")


def vendor_sql(account: VendorAccount) -> str:
    return f"""
INSERT INTO model_vendor_accounts
  (vendor_code, account_name, base_url, api_key, extra_auth_json, console_url, balance_url,
   balance_query_mode, balance_amount, balance_currency, health_status, enabled, is_deleted, created_at, updated_at)
SELECT {sql_string(account.vendor_code)}, {sql_string(account.account_name)}, {sql_string(account.base_url)},
       {sql_string(account.api_key)}, {sql_string(account.extra_auth_json)}, {sql_string(account.console_url)},
       {sql_string(account.balance_url)}, {sql_string(account.balance_query_mode)}, {sql_number(account.balance_amount)},
       {sql_string(account.balance_currency)}, 'OK', {account.enabled}, 0, NOW(), NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM model_vendor_accounts
  WHERE is_deleted=0 AND vendor_code={sql_string(account.vendor_code)} AND base_url={sql_string(account.base_url)}
);

UPDATE model_vendor_accounts
SET account_name={sql_string(account.account_name)},
    api_key={sql_string(account.api_key)},
    extra_auth_json={sql_string(account.extra_auth_json)},
    console_url={sql_string(account.console_url)},
    balance_url={sql_string(account.balance_url)},
    balance_query_mode={sql_string(account.balance_query_mode)},
    balance_amount={sql_number(account.balance_amount)},
    balance_currency={sql_string(account.balance_currency)},
    health_status='OK',
    enabled={account.enabled},
    updated_at=NOW()
WHERE is_deleted=0 AND vendor_code={sql_string(account.vendor_code)} AND base_url={sql_string(account.base_url)};
"""


def model_sql(model: ModelConfig, account: VendorAccount) -> str:
    account_id = (
        "SELECT MAX(id) FROM model_vendor_accounts "
        f"WHERE is_deleted=0 AND vendor_code={sql_string(account.vendor_code)} AND base_url={sql_string(account.base_url)} "
        "AND CHAR_LENGTH(COALESCE(api_key,'')) > 0"
    )
    values = (
        f"({account_id}), {sql_string(model.display_name)}, {sql_string(model.config_code)}, "
        f"{sql_string(model.provider)}, {sql_string(model.model_name)}, {sql_string(model.base_url)}, '', "
        f"{sql_string(model.minimax_group_id)}, {sql_string(model.console_url)}, {sql_string(model.balance_url)}, "
        f"{sql_string(model.docs_url)}, {model.timeout_seconds}, {model.input_token_price_per_1k}, "
        f"{model.output_token_price_per_1k}, {model.input_token_price_per_1m}, {model.output_token_price_per_1m}, "
        f"{sql_string(model.billing_unit)}, {model.unit_price}, {model.enabled}, {model.agent_enabled}, {model.is_default}, "
        f"0, NOW(), NOW(), {sql_string(model.capabilities)}, {sql_string(model.extra_auth_json)}, NULL, NULL, NULL"
    )
    return f"""
INSERT INTO agent_model_configs
  (vendor_account_id, display_name, config_code, provider, model_name, base_url, api_key,
   minimax_group_id, console_url, balance_url, docs_url, timeout_seconds,
   input_token_price_per_1k, output_token_price_per_1k, input_token_price_per_1m, output_token_price_per_1m,
   billing_unit, unit_price, enabled, agent_enabled, is_default, is_deleted, created_at, updated_at,
   capabilities, extra_auth_json, last_test_success, last_test_message, last_test_at)
SELECT {values}
WHERE NOT EXISTS (
  SELECT 1 FROM agent_model_configs WHERE is_deleted=0 AND config_code={sql_string(model.config_code)}
);

UPDATE agent_model_configs
SET vendor_account_id=({account_id}),
    display_name={sql_string(model.display_name)},
    provider={sql_string(model.provider)},
    model_name={sql_string(model.model_name)},
    base_url={sql_string(model.base_url)},
    api_key='',
    minimax_group_id={sql_string(model.minimax_group_id)},
    console_url={sql_string(model.console_url)},
    balance_url={sql_string(model.balance_url)},
    docs_url={sql_string(model.docs_url)},
    timeout_seconds={model.timeout_seconds},
    input_token_price_per_1k={model.input_token_price_per_1k},
    output_token_price_per_1k={model.output_token_price_per_1k},
    input_token_price_per_1m={model.input_token_price_per_1m},
    output_token_price_per_1m={model.output_token_price_per_1m},
    billing_unit={sql_string(model.billing_unit)},
    unit_price={model.unit_price},
    enabled={model.enabled},
    agent_enabled={model.agent_enabled},
    is_default={model.is_default},
    capabilities={sql_string(model.capabilities)},
    extra_auth_json={sql_string(model.extra_auth_json)},
    last_test_success=NULL,
    last_test_message=NULL,
    last_test_at=NULL,
    updated_at=NOW()
WHERE is_deleted=0 AND config_code={sql_string(model.config_code)};
"""


def tool_sql(tool: ToolConfig) -> str:
    category_id = (
        "COALESCE((SELECT id FROM tool_categories WHERE category_code="
        f"{sql_string(tool.category_code)} LIMIT 1), 1)"
    )
    model_id = (
        "(SELECT id FROM agent_model_configs WHERE is_deleted=0 AND config_code="
        f"{sql_string(tool.model_config_code)} LIMIT 1)"
    )
    return f"""
INSERT INTO ai_tools
  (tool_code, tool_name, category_id, description, cover_url, tool_type, input_modality, output_modality,
   config_note, status, estimated_credit_cost, model_config_id, execution_handler, is_deleted, created_at, updated_at)
SELECT {sql_string(tool.tool_code)}, {sql_string(tool.tool_name)}, {category_id}, {sql_string(tool.description)},
       {sql_string(tool.cover_url)}, {sql_string(tool.tool_type)}, {sql_string(tool.input_modality)},
       {sql_string(tool.output_modality)}, {sql_string(tool.config_note)}, {sql_string(tool.status)},
       {tool.estimated_credit_cost}, {model_id}, {sql_string(tool.execution_handler)}, 0, NOW(), NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM ai_tools WHERE is_deleted=0 AND tool_code={sql_string(tool.tool_code)}
);

UPDATE ai_tools
SET tool_name={sql_string(tool.tool_name)},
    category_id={category_id},
    description={sql_string(tool.description)},
    cover_url={sql_string(tool.cover_url)},
    tool_type={sql_string(tool.tool_type)},
    input_modality={sql_string(tool.input_modality)},
    output_modality={sql_string(tool.output_modality)},
    config_note={sql_string(tool.config_note)},
    status={sql_string(tool.status)},
    estimated_credit_cost={tool.estimated_credit_cost},
    model_config_id={model_id},
    execution_handler={sql_string(tool.execution_handler)},
    updated_at=NOW()
WHERE is_deleted=0 AND tool_code={sql_string(tool.tool_code)};
"""


def run_remote(sql: str) -> None:
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username="root", password=PASSWORD, timeout=30, allow_agent=False, look_for_keys=False)
    sftp = ssh.open_sftp()
    with sftp.file("/tmp/sync_media_configs.sql", "w") as remote_file:
        remote_file.write(sql)
    sftp.close()
    _, stdout, stderr = ssh.exec_command(
        "docker compose -f /root/ai_tool_market/deploy/docker-compose.yml exec -T mysql "
        "mysql -uroot -proot123456 ai_supermarket_v1 < /tmp/sync_media_configs.sql",
        timeout=120,
    )
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    ssh.close()
    if out.strip():
        print(out.strip())
    if err.strip():
        print(err.strip(), file=sys.stderr)
    if code != 0:
        raise RuntimeError(f"remote SQL failed with code {code}")


def main() -> int:
    if not PASSWORD:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1
    bundle = load_bundle()
    ofox_account = bundle_vendor(bundle, "openai_gateway", "https://api.ofox.ai/v1")
    suno_account = bundle_vendor(bundle, "suno_music", "https://api.sunoapi.org")
    ofox_model = bundle_model(bundle, "9")
    suno_model = bundle_model(bundle, "SunoV5_5")
    tools = [bundle_tool(bundle, code) for code in ("suno", "suno_music")]
    sql = "START TRANSACTION;\n"
    sql += vendor_sql(ofox_account)
    sql += vendor_sql(suno_account)
    sql += model_sql(ofox_model, ofox_account)
    sql += model_sql(suno_model, suno_account)
    for tool in tools:
        sql += tool_sql(tool)
    sql += """
UPDATE ai_tools
SET model_config_id=(SELECT id FROM agent_model_configs WHERE is_deleted=0 AND config_code='9' LIMIT 1),
    status='ONLINE',
    execution_handler='IMAGE_GENERATION',
    tool_type='IMAGE_GENERATION',
    output_modality='IMAGE',
    updated_at=NOW()
WHERE is_deleted=0 AND tool_code='ofox_gpt_image2';
"""
    sql += "\nCOMMIT;\n"
    run_remote(sql)
    print("Synced media model/tool configs without printing secret values.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

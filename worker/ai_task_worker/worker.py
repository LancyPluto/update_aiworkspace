import logging
import signal
import sys
from typing import Optional

from ai_task_worker.config import Settings, get_settings
from ai_task_worker.internal_api import InternalTaskClient
from ai_task_worker.model_client import OpenAiCompatibleModelClient
from ai_task_worker.prompt import render_user_prompt
from ai_task_worker.redis_consumer import RedisTaskConsumer
from ai_task_worker.schemas import FailedPayload, SuccessPayload, TaskQueueMessage

logger = logging.getLogger(__name__)

_stop = False


def _handle_sig(_signum, _frame) -> None:
    global _stop
    _stop = True


def run_worker(settings: Optional[Settings] = None) -> None:
    settings = settings or get_settings()
    signal.signal(signal.SIGINT, _handle_sig)
    signal.signal(signal.SIGTERM, _handle_sig)

    consumer = RedisTaskConsumer(settings)
    consumer.ping()
    logger.info(
        "Worker 已启动 queue=%s redis=%s:%s dry_run=%s",
        settings.ai_task_queue,
        settings.redis_host,
        settings.redis_port,
        settings.worker_dry_run,
    )

    internal = InternalTaskClient(settings)
    model = OpenAiCompatibleModelClient(settings)

    try:
        for msg in consumer.iter_messages():
            if _stop:
                logger.info("收到退出信号，结束进程")
                break
            if msg is None:
                continue
            try:
                _process_one(settings, internal, model, msg)
            except Exception:
                logger.exception("任务处理异常 task_id=%s", msg.task_id)
    finally:
        internal.close()
        model.close()


def _process_one(
    settings: Settings,
    internal: InternalTaskClient,
    model: OpenAiCompatibleModelClient,
    msg: TaskQueueMessage,
) -> None:
    if settings.worker_dry_run:
        logger.info("[dry-run] 收到任务 task_id=%s tool=%s", msg.task_id, msg.tool_code)
        return

    ctx = internal.get_execution_context(msg.task_id)
    internal.post_processing(msg.task_id)

    user_prompt, missing = render_user_prompt(ctx.user_prompt_template, ctx.params)
    if missing:
        internal.post_failed(
            msg.task_id,
            FailedPayload(
                errorCode="PROMPT_VARIABLE_MISSING",
                errorMessage=f"缺少变量: {', '.join(missing)}",
            ),
        )
        return

    try:
        text = model.chat(
            model=ctx.model_name,
            system_prompt=ctx.system_prompt,
            user_content=user_prompt,
        )
    except Exception as e:
        code = "MODEL_TIMEOUT" if "timeout" in str(e).lower() else "MODEL_CALL_FAILED"
        internal.post_failed(
            msg.task_id,
            FailedPayload(errorCode=code, errorMessage=str(e)[:500]),
        )
        return

    internal.post_success(
        msg.task_id,
        SuccessPayload(
            resourceType=ctx.output_format,
            contentText=text,
            contentJson=None,
            modelProviderCode=ctx.model_provider_code,
            modelName=ctx.model_name,
        ),
    )
    logger.info("任务完成 task_id=%s", msg.task_id)


def configure_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        stream=sys.stdout,
    )

from setuptools import find_packages, setup

setup(
    name="ai-task-worker",
    version="0.1.0",
    description="AI 超市：Redis 队列消费、模型调用、任务状态回写",
    python_requires=">=3.9",
    packages=find_packages(exclude=("tests",)),
    install_requires=[
        "httpx>=0.27,<1",
        "pydantic>=2,<3",
        "pydantic-settings>=2,<3",
        "redis>=5,<6",
    ],
    extras_require={"dev": ["ruff>=0.6"]},
    entry_points={
        "console_scripts": [
            "ai-task-worker=ai_task_worker.__main__:main",
        ],
    },
)

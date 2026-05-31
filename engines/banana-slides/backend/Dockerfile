# 镜像源配置参数（可通过 build args 覆盖）
ARG DOCKER_REGISTRY=
ARG APT_MIRROR=mirrors.aliyun.com
ARG PYPI_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple


# 使用 Python 3.10 作为基础镜像
# 如果指定了 DOCKER_REGISTRY，使用镜像源；否则使用官方源
FROM ${DOCKER_REGISTRY:-}python:3.12-slim

# 重新声明ARG（FROM之后ARG作用域失效，需要重新声明）
ARG APT_MIRROR=mirrors.aliyun.com
ARG PYPI_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple

# 设置工作目录
WORKDIR /app

# 安装系统依赖（默认使用国内 APT 镜像，可通过 build-arg APT_MIRROR 覆盖）
COPY docker/debian-apt-mirror.sh /usr/local/bin/debian-apt-mirror.sh
RUN sed -i 's/\r$//' /usr/local/bin/debian-apt-mirror.sh \
    && chmod +x /usr/local/bin/debian-apt-mirror.sh \
    && APT_MIRROR="${APT_MIRROR}" sh /usr/local/bin/debian-apt-mirror.sh \
    && apt-get update && apt-get install -y \
    curl \
    ffmpeg \
    fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*

# 通过 pip 安装 uv
RUN pip install --no-cache-dir -i https://mirrors.aliyun.com/pypi/simple/ uv

# 复制项目配置文件
COPY pyproject.toml ./
COPY uv.lock* ./

# 配置 PyPI 镜像源（如果指定）
ENV UV_INDEX_URL=${PYPI_INDEX_URL}

# 配置 uv 网络超时
ENV UV_HTTP_TIMEOUT=300

# 安装 Python 依赖
# 如果有 uv.lock 文件则使用 --frozen，否则生成新的锁定文件
RUN if [ -s uv.lock ]; then \
        uv sync --frozen; \
    else \
        rm -f uv.lock && uv sync; \
    fi

# 复制后端代码
COPY backend/ ./backend/

# 复制测试资源
COPY assets/ ./assets/

# 创建必要的目录
RUN mkdir -p /app/backend/instance /app/uploads

ENV PYTHONPATH=/app
ENV FLASK_APP=backend/app.py
# 容器内固定监听 5000；宿主机端口由 docker-compose 的 BACKEND_PORT 控制
ENV IN_DOCKER=1

# 暴露端口
EXPOSE 5000

# 容器内部固定使用 5000 端口
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD ["sh", "-c", "curl -f http://localhost:5000/health || exit 1"]

# 启动应用
CMD ["sh", "-c", "uv run --directory backend alembic upgrade head && uv run --directory backend python app.py"]


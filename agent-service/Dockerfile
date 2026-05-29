# Build from repository root: docker build -f agent-service/Dockerfile .
FROM python:3.12-slim-bookworm

ARG APT_MIRROR=mirrors.aliyun.com
ENV APT_MIRROR=${APT_MIRROR}
ENV PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/
ENV PIP_TRUSTED_HOST=mirrors.aliyun.com

WORKDIR /app

COPY deploy/docker/debian-apt-mirror.sh /usr/local/bin/debian-apt-mirror.sh
RUN chmod +x /usr/local/bin/debian-apt-mirror.sh && debian-apt-mirror.sh

COPY agent-service/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY agent-service/app ./app

EXPOSE 8090

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]

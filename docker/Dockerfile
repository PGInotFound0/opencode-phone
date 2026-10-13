FROM debian:12-slim

ARG OPENCODE_VERSION=1.19.0
ENV DEBIAN_FRONTEND=noninteractive

# curl is kept for the built-in health check; git for opencode's diff/context support
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl git \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL https://opencode.ai/install -o /tmp/install.sh \
 && if [ -n "$OPENCODE_VERSION" ]; then \
      bash /tmp/install.sh -v "$OPENCODE_VERSION" --no-modify-path; \
    else \
      bash /tmp/install.sh --no-modify-path; \
    fi \
 && mv /root/.opencode/bin/opencode /usr/local/bin/opencode \
 && chmod +x /usr/local/bin/opencode \
 && rm -f /tmp/install.sh

ENV HOME=/root
WORKDIR /workspace
EXPOSE 4096

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD curl -fsS http://127.0.0.1:4096/global/health || exit 1

ENTRYPOINT ["opencode", "serve", "--port", "4096", "--hostname", "0.0.0.0"]

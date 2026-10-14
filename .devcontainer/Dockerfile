FROM mcr.microsoft.com/devcontainers/java:1-17-bookworm
USER root
# The image's Yarn apt source fails signature checks, which breaks apt-get update and the Docker feature. Yarn isn't used.
RUN (grep -rl dl.yarnpkg.com /etc/apt/sources.list.d/ || true) | xargs -r rm -f

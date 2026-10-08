#!/bin/bash
# Build custom Ubuntu rootfs for Botix terminal
# Run this on a Linux machine or in GitHub Actions

set -euo pipefail

REPO="algospider/Botix-Packages"
TAG="ubuntu-24.04-dev-$(date +%Y%m%d)"
ROOTFS_DIR="/tmp/botix-rootfs"

# Packages to pre-install in the rootfs
PACKAGES=(
    # Core build tools
    "build-essential" "cmake" "pkg-config" "libssl-dev" "libffi-dev"
    # Version control
    "git" "git-lfs"
    # Network/download
    "curl" "wget" "rsync" "ca-certificates"
    # Python (AI/ML focus)
    "python3" "python3-pip" "python3-venv" "python3-dev" "python3-full"
    # Node.js (for agents/tools)
    "nodejs" "npm"
    # Utilities
    "jq" "tree" "htop" "vim" "nano" "unzip" "zip" "tar" "gzip" "bzip2" "xz-utils"
    # Terminal multiplexer
    "tmux" "screen"
    # Shell enhancements
    "zsh" "fish" "command-not-found" "sudo" "xkb-data"
    # Process/tools
    "procps" "lsof" "strace" "ltrace"
    # Compression
    "p7zip-full" "p7zip-rar"
    # Editor/IDE support
    "ripgrep" "fd-find" "bat" "eza"
)

# Dockerfile for building the rootfs
cat > /tmp/Dockerfile.rootfs << 'EOF'
FROM ubuntu:24.04

# Avoid interactive prompts
ENV DEBIAN_FRONTEND=noninteractive

# Install packages
RUN apt-get update && apt-get install -y --no-install-recommends \
    PACKAGES_PLACEHOLDER \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user (UID 1000 matches typical Android app)
RUN useradd -m -u 1000 -s /bin/bash botix \
    && echo "botix ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/botix

# Set up locale
RUN locale-gen en_US.UTF-8
ENV LANG=en_US.UTF-8
ENV LC_ALL=en_US.UTF-8

# Create working directory
WORKDIR /home/botix

# Default shell
SHELL ["/bin/bash", "-c"]
EOF

# Replace placeholder with actual packages
PACKAGES_STR="${PACKAGES[*]}"
sed -i "s/PACKAGES_PLACEHOLDER/${PACKAGES_STR}/g" /tmp/Dockerfile.rootfs

echo "Building Docker image..."
docker build -f /tmp/Dockerfile.rootfs -t botix-rootfs:latest /tmp

echo "Creating rootfs tarballs for each architecture..."

# Build for each architecture
for ARCH in amd64 arm64 armhf; do
    case $ARCH in
        amd64) PLATFORM="linux/amd64" ;;
        arm64) PLATFORM="linux/arm64" ;;
        armhf) PLATFORM="linux/arm/v7" ;;
    esac
    
    echo "Building for $ARCH ($PLATFORM)..."
    
    # Create a temporary container to extract rootfs
    CID=$(docker create --platform $PLATFORM botix-rootfs:latest)
    docker export $CID | gzip > "/tmp/ubuntu-base-24.04.3-dev-${ARCH}.tar.gz"
    docker rm $CID
    
    echo "Created: /tmp/ubuntu-base-24.04.3-dev-${ARCH}.tar.gz"
done

echo ""
echo "========================================="
echo "Rootfs tarballs created:"
ls -lh /tmp/ubuntu-base-24.04.3-dev-*.tar.gz
echo "========================================="
echo ""
echo "To upload to GitHub releases:"
echo "  gh release create $TAG /tmp/ubuntu-base-24.04.3-dev-*.tar.gz --repo $REPO --title \"Ubuntu 24.04 Dev Rootfs\" --notes \"Pre-built rootfs with dev tools\""
echo ""
echo "Or manually upload to: https://github.com/$REPO/releases/new"
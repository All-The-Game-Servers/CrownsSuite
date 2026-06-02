# Progenitor Console — tri-platform build targets.
#
# Each target produces a single binary with the frontend embedded.
# Linux builds work in any CI. Windows/macOS builds need their respective
# system toolchains (MinGW-w64 or Xcode) available; Wails will surface a
# clear error if they're missing.

APP_NAME := progenitor
VERSION  := 0.5.0-phase5
BUILD_FLAGS := -trimpath -ldflags "-s -w -X main.version=$(VERSION)"

.PHONY: all frontend linux windows macos clean doctor

all: linux windows macos
	@echo ""
	@echo "Binaries in build/bin/:"
	@ls -lh build/bin/ 2>/dev/null || true

# The frontend is built by Wails as part of `wails build`. This target is
# here for the case where you want to iterate on just the frontend without
# triggering a full Go compile.
frontend:
	cd frontend && npm install && npm run build

# On Linux, we need the webkit2_41 build tag for the 4.1 packaging of libwebkit.
# 4.0 is the older Ubuntu package name; 4.1 is current (24.04+).
linux:
	wails build -platform linux/amd64 -tags webkit2_41 $(BUILD_FLAGS) -o $(APP_NAME)-linux-amd64

# Windows produces an .exe. Requires MinGW-w64 cross toolchain if building
# from Linux/macOS (apt install gcc-mingw-w64 / brew install mingw-w64).
# On a Windows dev machine, `wails build` alone works.
windows:
	wails build -platform windows/amd64 $(BUILD_FLAGS) -o $(APP_NAME)-windows-amd64.exe
	wails build -platform windows/arm64 $(BUILD_FLAGS) -o $(APP_NAME)-windows-arm64.exe

# macOS universal binary — contains both amd64 and arm64 slices. Must be
# built on a macOS host with Xcode Command Line Tools installed; cross-
# compiling macOS binaries from other platforms is not supported by the
# Wails toolchain (Cocoa framework dependency).
macos:
	wails build -platform darwin/universal $(BUILD_FLAGS) -o $(APP_NAME)-darwin-universal

# Dev mode with hot-reload frontend (http://localhost:34115 by default).
dev:
	wails dev -tags webkit2_41

# Wails self-check — reports missing dependencies for the host platform.
doctor:
	wails doctor

clean:
	rm -rf build/bin frontend/dist
	@echo "Cleaned build artifacts."

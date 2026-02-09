# Application.mk for Moonlight

# Our minimum version is Android 5.0
APP_PLATFORM := android-34

# We support 16KB pages
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true

# Only support 64-bit ARM architecture (remove armv7 and x86 support)
APP_ABI := arm64-v8a


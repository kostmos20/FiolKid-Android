# Нативная часть: hev-socks5-tunnel (трафик телефона из VPN → в локальный SOCKS) и byedpi (сам обход DPI).
# Исходники обоих — как есть с GitHub (MIT), см. README в их папках.

JNI_DIR := $(call my-dir)

include $(JNI_DIR)/hev-socks5-tunnel/Android.mk

LOCAL_PATH := $(JNI_DIR)
include $(CLEAR_VARS)
LOCAL_MODULE := byedpi
LOCAL_SRC_FILES := \
    byedpi/packets.c byedpi/main.c byedpi/conev.c byedpi/proxy.c \
    byedpi/desync.c byedpi/mpool.c byedpi/extend.c \
    byedpi-jni.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/byedpi
# main() byedpi переименован: его вызывает byedpi-jni.c с аргументами-стратегией
LOCAL_CFLAGS := -std=c99 -O2 -D_DEFAULT_SOURCE -Dmain=ciadpi_main -Wno-unused -Wno-unused-parameter
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := extension_fixture
LOCAL_SRC_FILES := probe.c
include $(BUILD_SHARED_LIBRARY)

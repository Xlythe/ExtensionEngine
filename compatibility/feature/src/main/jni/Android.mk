LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := extension_fixture_value
LOCAL_SRC_FILES := value.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := extension_fixture
LOCAL_SRC_FILES := probe.c
LOCAL_SHARED_LIBRARIES := extension_fixture_value
include $(BUILD_SHARED_LIBRARY)

#include <jni.h>

extern int fixture_value(void);

JNIEXPORT jint JNICALL Java_com_xlythe_test_feature_NativeProbe_read(JNIEnv *env, jclass type) {
    return fixture_value();
}

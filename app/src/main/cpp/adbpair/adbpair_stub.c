/*
 * R100 ③: adb_pair stub for non-arm64-v8a ABIs.
 *
 * BoringSSL is prebuilt for arm64-v8a only (real Android 11+ handsets). On the
 * other ABIs we still emit libadbpair.so so System.loadLibrary succeeds, but
 * every call reports UNSUPPORTED_ABI. Same JNI symbol names as adbpair_jni.cpp.
 */
#include <jni.h>

JNIEXPORT jstring JNICALL
Java_com_dc_andagent_tools_AdbPairNative_nativePair(JNIEnv* env, jobject thiz, jstring host,
                                                   jint port, jstring code, jbyteArray adb_pubkey,
                                                   jstring device_name) {
    (void)thiz;
    (void)host;
    (void)port;
    (void)code;
    (void)adb_pubkey;
    (void)device_name;
    return (*env)->NewStringUTF(env, "ERROR\nREASON=UNSUPPORTED_ABI\n");
}

JNIEXPORT jstring JNICALL
Java_com_dc_andagent_tools_AdbPairNative_nativeSelfTest(JNIEnv* env, jobject thiz) {
    (void)thiz;
    return (*env)->NewStringUTF(env, "UNSUPPORTED_ABI");
}

// mallopt(M_PURGE, 0): asks bionic's allocator (Scudo on Android 11+, jemalloc on 9-10) to return the pages
// of freed allocations to the system. Returns 1 when the allocator did it, 0 when it doesn't support it.
#include <jni.h>
#include <malloc.h> // M_PURGE: API 28+

JNIEXPORT jint JNICALL
Java_com_glasslauncher_tv_NativeHeap_nativePurge(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return mallopt(M_PURGE, 0);
}

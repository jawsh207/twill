// Twill: a minimal JNI bridge to the Delta Chat core (chatmail core's deltachat-ffi).
//
// Twill talks to the core only through JSON-RPC (dc_jsonrpc_blocking_call) and reads core
// events through an event emitter, so this file stays tiny. Strings cross the boundary as
// UTF-8 byte arrays, because JNI's NewStringUTF expects "modified UTF-8" and mangles emoji.
//
// Built by scripts/build-deltachat-core.sh into libtwill_deltachat.so, which includes the
// core itself (linked statically; only these Java_* functions are exported).

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "deltachat.h"

#define FN(name) Java_com_android_messaging_deltachat_DeltaChatNative_##name

static char* to_c_string(JNIEnv* env, jbyteArray bytes) {
    jsize length = (*env)->GetArrayLength(env, bytes);
    char* out = malloc((size_t)length + 1);
    if (out == NULL) return NULL;
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte*)out);
    out[length] = '\0';
    return out;
}

static jbyteArray to_byte_array(JNIEnv* env, const char* text) {
    jsize length = (jsize)strlen(text);
    jbyteArray out = (*env)->NewByteArray(env, length);
    if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, length, (const jbyte*)text);
    return out;
}

JNIEXPORT jlong JNICALL FN(accountsNew)(JNIEnv* env, jclass cls, jbyteArray dir) {
    char* path = to_c_string(env, dir);
    if (path == NULL) return 0;
    dc_accounts_t* accounts = dc_accounts_new(path, 1);
    free(path);
    return (jlong)(intptr_t)accounts;
}

JNIEXPORT void JNICALL FN(accountsUnref)(JNIEnv* env, jclass cls, jlong accounts) {
    dc_accounts_unref((dc_accounts_t*)(intptr_t)accounts);
}

JNIEXPORT jlong JNICALL FN(jsonrpcInit)(JNIEnv* env, jclass cls, jlong accounts) {
    return (jlong)(intptr_t)dc_jsonrpc_init((dc_accounts_t*)(intptr_t)accounts);
}

JNIEXPORT void JNICALL FN(jsonrpcUnref)(JNIEnv* env, jclass cls, jlong rpc) {
    dc_jsonrpc_unref((dc_jsonrpc_instance_t*)(intptr_t)rpc);
}

// Blocks the calling thread until the core answers. Returns the response, or null.
JNIEXPORT jbyteArray JNICALL FN(jsonrpcCall)(JNIEnv* env, jclass cls, jlong rpc, jbyteArray request) {
    char* input = to_c_string(env, request);
    if (input == NULL) return NULL;
    char* response = dc_jsonrpc_blocking_call((dc_jsonrpc_instance_t*)(intptr_t)rpc, input);
    free(input);
    if (response == NULL) return NULL;
    jbyteArray out = to_byte_array(env, response);
    dc_str_unref(response);
    return out;
}

JNIEXPORT jlong JNICALL FN(eventEmitterNew)(JNIEnv* env, jclass cls, jlong accounts) {
    return (jlong)(intptr_t)dc_accounts_get_event_emitter((dc_accounts_t*)(intptr_t)accounts);
}

JNIEXPORT void JNICALL FN(eventEmitterUnref)(JNIEnv* env, jclass cls, jlong emitter) {
    dc_event_emitter_unref((dc_event_emitter_t*)(intptr_t)emitter);
}

// Blocks until the next event. Returns {id, accountId, data1, data2}, or null once the
// account manager has been freed and no more events will come.
JNIEXPORT jintArray JNICALL FN(nextEvent)(JNIEnv* env, jclass cls, jlong emitter) {
    dc_event_t* event = dc_get_next_event((dc_event_emitter_t*)(intptr_t)emitter);
    if (event == NULL) return NULL;
    int id = dc_event_get_id(event);
    jint values[4] = {
        id,
        (jint)dc_event_get_account_id(event),
        dc_event_get_data1_int(event),
        DC_EVENT_DATA2_IS_STRING(id) ? 0 : dc_event_get_data2_int(event),
    };
    dc_event_unref(event);
    jintArray out = (*env)->NewIntArray(env, 4);
    if (out != NULL) (*env)->SetIntArrayRegion(env, out, 0, 4, values);
    return out;
}

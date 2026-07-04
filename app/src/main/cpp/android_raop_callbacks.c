/*
 * Implements raop_callbacks_t by forwarding to Java/Kotlin via JNI.
 * All callbacks fire from RAOP's internal pthreads, so we AttachCurrentThread.
 *
 * Frame buffer pool: pre-allocated DirectByteBuffers avoid per-frame jbyteArray
 * allocations (~120MB/s GC pressure at 1080p60). Native writes frame data directly
 * into pool buffers, passes ByteBuffer to Kotlin, which returns it after MediaCodec
 * consumption.
 */

#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <android/log.h>
#include "android_raop_callbacks.h"

#define TAG "AirCastNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* TLS key to track whether we called AttachCurrentThread on this thread */
static pthread_key_t _jni_attach_key;
static pthread_once_t _jni_attach_key_once = PTHREAD_ONCE_INIT;

static void _jni_detach_callback(void *jvm_ptr) {
    if (jvm_ptr) {
        JavaVM *jvm = (JavaVM *)jvm_ptr;
        (*jvm)->DetachCurrentThread(jvm);
    }
}

static void _jni_init_key(void) {
    pthread_key_create(&_jni_attach_key, _jni_detach_callback);
}

static JNIEnv *_get_env(android_callback_ctx_t *ctx) {
    JNIEnv *env = NULL;
    int status = (*ctx->jvm)->GetEnv(ctx->jvm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        (*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, NULL);
        if (env) {
            pthread_once(&_jni_attach_key_once, _jni_init_key);
            pthread_setspecific(_jni_attach_key, ctx->jvm);
        }
    }
    /* Clear any pending exception from a previous callback on this thread,
       otherwise JNI calls like NewByteArray will fatally abort. */
    if (env && (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    return env;
}

/* --- Frame buffer pool --- */

int android_frame_pool_init(android_callback_ctx_t *ctx, JNIEnv *env,
                            jobjectArray buffers, jint count) {
    if (count > FRAME_POOL_SIZE) count = FRAME_POOL_SIZE;

    pthread_mutex_init(&ctx->frame_pool_lock, NULL);
    sem_init(&ctx->frame_pool_sem, 0, count);

    for (int i = 0; i < count; i++) {
        jobject buf = (*env)->GetObjectArrayElement(env, buffers, i);
        ctx->frame_buffers[i] = (*env)->NewGlobalRef(env, buf);
        ctx->frame_addrs[i] = (uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
        ctx->frame_pool_free[i] = i;
        (*env)->DeleteLocalRef(env, buf);
    }
    ctx->frame_pool_head = count;
    ctx->frame_pool_initialized = 1;

    LOGI("Frame buffer pool initialized: %d buffers, %d bytes each",
         count, FRAME_BUFFER_SIZE);
    return 0;
}

void android_frame_pool_return(android_callback_ctx_t *ctx, jobject buffer) {
    if (!ctx->frame_pool_initialized) return;

    /* Find which pool buffer this corresponds to */
    for (int i = 0; i < FRAME_POOL_SIZE; i++) {
        if (ctx->frame_buffers[i] == NULL) continue;

        JNIEnv *env = _get_env(ctx);
        if (!env) return;

        if ((*env)->IsSameObject(env, ctx->frame_buffers[i], buffer)) {
            pthread_mutex_lock(&ctx->frame_pool_lock);
            ctx->frame_pool_free[ctx->frame_pool_head++] = i;
            pthread_mutex_unlock(&ctx->frame_pool_lock);
            sem_post(&ctx->frame_pool_sem);
            return;
        }
    }
    LOGE("android_frame_pool_return: unknown buffer object");
}

void android_frame_pool_destroy(android_callback_ctx_t *ctx, JNIEnv *env) {
    if (!ctx->frame_pool_initialized) return;

    for (int i = 0; i < FRAME_POOL_SIZE; i++) {
        if (ctx->frame_buffers[i]) {
            (*env)->DeleteGlobalRef(env, ctx->frame_buffers[i]);
            ctx->frame_buffers[i] = NULL;
        }
    }
    pthread_mutex_destroy(&ctx->frame_pool_lock);
    sem_destroy(&ctx->frame_pool_sem);
    ctx->frame_pool_initialized = 0;
    LOGI("Frame buffer pool destroyed");
}

/* Pop a free buffer from the pool. Blocks on semaphore (indefinitely). */
static int _frame_pool_acquire(android_callback_ctx_t *ctx) {
    sem_wait(&ctx->frame_pool_sem);

    pthread_mutex_lock(&ctx->frame_pool_lock);
    int idx = ctx->frame_pool_free[--ctx->frame_pool_head];
    pthread_mutex_unlock(&ctx->frame_pool_lock);

    return idx;
}

/* --- JNI helpers --- */

void android_callbacks_init(android_callback_ctx_t *ctx, JNIEnv *env, jobject callback_obj) {
    (*env)->GetJavaVM(env, &ctx->jvm);
    ctx->callback_obj = (*env)->NewGlobalRef(env, callback_obj);
    ctx->h265_enabled = 1;
    ctx->require_pin = 0;
    ctx->registered_count = 0;
    memset(ctx->registered_keys, 0, sizeof(ctx->registered_keys));

    /* Frame pool not yet initialized */
    ctx->frame_pool_initialized = 0;
    memset(ctx->frame_buffers, 0, sizeof(ctx->frame_buffers));
    memset(ctx->frame_addrs, 0, sizeof(ctx->frame_addrs));

    jclass cls = (*env)->GetObjectClass(env, callback_obj);
    /* onVideoData now takes ByteBuffer instead of byte[] */
    ctx->on_video_data = (*env)->GetMethodID(env, cls, "onVideoData",
        "(Ljava/nio/ByteBuffer;JZ)V");
    ctx->on_audio_data = (*env)->GetMethodID(env, cls, "onAudioData", "([BIJI)V");
    ctx->on_audio_format = (*env)->GetMethodID(env, cls, "onAudioFormat", "(IIZ)V");
    ctx->on_video_size = (*env)->GetMethodID(env, cls, "onVideoSize", "(FFFF)V");
    ctx->on_volume_change = (*env)->GetMethodID(env, cls, "onVolumeChange", "(F)V");
    ctx->on_conn_init = (*env)->GetMethodID(env, cls, "onConnectionInit", "()V");
    ctx->on_conn_destroy = (*env)->GetMethodID(env, cls, "onConnectionDestroy", "()V");
    ctx->on_conn_reset = (*env)->GetMethodID(env, cls, "onConnectionReset", "(I)V");
    ctx->on_display_pin = (*env)->GetMethodID(env, cls, "onDisplayPin", "(Ljava/lang/String;)V");
    ctx->on_metadata = (*env)->GetMethodID(env, cls, "onMetadata", "([B)V");
    ctx->on_coverart = (*env)->GetMethodID(env, cls, "onCoverArt", "([B)V");
    ctx->on_progress = (*env)->GetMethodID(env, cls, "onProgress", "(JJJ)V");
    ctx->on_dacp_id = (*env)->GetMethodID(env, cls, "onDacpId", "(Ljava/lang/String;Ljava/lang/String;)V");
    ctx->on_audio_only = (*env)->GetMethodID(env, cls, "onAudioOnly", "(Z)V");
    ctx->on_video_pause = (*env)->GetMethodID(env, cls, "onVideoPause", "()V");
    ctx->on_video_resume = (*env)->GetMethodID(env, cls, "onVideoResume", "()V");
    ctx->on_video_reset = (*env)->GetMethodID(env, cls, "onVideoReset", "(I)V");
    ctx->on_audio_flush = (*env)->GetMethodID(env, cls, "onAudioFlush", "()V");
    ctx->on_video_flush = (*env)->GetMethodID(env, cls, "onVideoFlush", "()V");
    (*env)->DeleteLocalRef(env, cls);
}

void android_callbacks_destroy(android_callback_ctx_t *ctx, JNIEnv *env) {
    if (ctx->callback_obj) {
        (*env)->DeleteGlobalRef(env, ctx->callback_obj);
        ctx->callback_obj = NULL;
    }
    for (int i = 0; i < ctx->registered_count; i++) {
        free(ctx->registered_keys[i]);
        ctx->registered_keys[i] = NULL;
    }
    ctx->registered_count = 0;
}

/* --- RAOP callback implementations --- */

static void _audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !data->data || data->data_len <= 0) return;

    jbyteArray arr = (*env)->NewByteArray(env, data->data_len);
    if (!arr) return;  /* OOM */
    (*env)->SetByteArrayRegion(env, arr, 0, data->data_len, (jbyte *)data->data);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_audio_data,
                           arr, (jint)data->ct, (jlong)data->ntp_time_local, (jint)data->seqnum);
    (*env)->DeleteLocalRef(env, arr);
}

/**
 * Video frame callback — uses frame buffer pool instead of per-frame jbyteArray.
 *
 * Flow:
 * 1. Acquire a pool DirectByteBuffer (blocks on semaphore if pool empty)
 * 2. memcpy frame data into the buffer's native address
 * 3. Set buffer position=0, limit=data_len
 * 4. Call Java onVideoData(ByteBuffer, ntpTime, isH265)
 * 5. Java processes frame asynchronously, returns buffer via nativeReturnFrameBuffer
 *
 * This eliminates per-frame JNI NewByteArray + SetByteArrayRegion overhead
 * and drastically reduces GC pressure (~120MB/s → 0MB/s allocation).
 */
static void _video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    if (!data->data || data->data_len <= 0) return;
    if (data->data_len > FRAME_BUFFER_SIZE) {
        LOGE("Frame too large: %d bytes (max %d)", data->data_len, FRAME_BUFFER_SIZE);
        return;
    }
    if (!ctx->frame_pool_initialized) return;

    JNIEnv *env = _get_env(ctx);
    if (!env) return;

    /* Acquire a free buffer from the pool (blocks until available) */
    int buf_idx = _frame_pool_acquire(ctx);

    /* Copy frame data directly into the DirectByteBuffer's native memory */
    memcpy(ctx->frame_addrs[buf_idx], data->data, data->data_len);

    /* Set the ByteBuffer's position and limit via JNI to reflect actual data size */
    jobject buf = ctx->frame_buffers[buf_idx];
    jclass bufClass = (*env)->GetObjectClass(env, buf);
    jmethodID clearMethod = (*env)->GetMethodID(env, bufClass, "clear", "()Ljava/nio/Buffer;");
    jmethodID limitMethod = (*env)->GetMethodID(env, bufClass, "limit", "(I)Ljava/nio/Buffer;");
    (*env)->CallObjectMethod(env, buf, clearMethod);
    (*env)->CallObjectMethod(env, buf, limitMethod, (jint)data->data_len);
    (*env)->DeleteLocalRef(env, bufClass);

    /* Pass to Java */
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_data,
                           buf, (jlong)data->ntp_time_local, (jboolean)data->is_h265);
}

static void _conn_init(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_conn_init);
}

static void _conn_destroy(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_conn_destroy);
}

static void _conn_reset(void *cls, int reason) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_conn_reset, (jint)reason);
}

static void _audio_set_volume(void *cls, float volume) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_volume_change, (jfloat)volume);
}

static void _audio_get_format(void *cls, unsigned char *ct, unsigned short *spf,
                               bool *usingScreen, bool *isMedia, uint64_t *audioFormat) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_audio_format,
                           (jint)*ct, (jint)*spf, (jboolean)*usingScreen);
}

static void _video_report_size(void *cls, float *w_src, float *h_src, float *w, float *h) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_size,
                           (jfloat)*w_src, (jfloat)*h_src, (jfloat)*w, (jfloat)*h);
}

static void _display_pin(void *cls, char *pin) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    jstring jpin = (*env)->NewStringUTF(env, pin);
    if (!jpin) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_display_pin, jpin);
    (*env)->DeleteLocalRef(env, jpin);
}

/* Stubs for less critical callbacks — now forwarded to Kotlin */
static void _noop(void *cls) { (void)cls; }
static void _noop_teardown(void *cls, bool *a, bool *b) { (void)cls; (void)a; (void)b; }

static void _video_pause(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_pause);
}

static void _video_resume(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_resume);
}

static void _conn_feedback(void *cls) { (void)cls; }

static void _video_reset(void *cls, reset_type_t t) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_reset, (jint)t);
}

static void _audio_flush(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_audio_flush);
}

static void _video_flush(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_flush);
}

static double _audio_set_client_volume(void *cls) { return 1.0; } /* default: full volume */
static void _audio_set_metadata(void *cls, const void *buf, int len) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !buf || len <= 0) return;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (!arr) return;
    (*env)->SetByteArrayRegion(env, arr, 0, len, (jbyte *)buf);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_metadata, arr);
    (*env)->DeleteLocalRef(env, arr);
}

static void _audio_set_coverart(void *cls, const void *buf, int len) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !buf || len <= 0) return;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (!arr) return;
    (*env)->SetByteArrayRegion(env, arr, 0, len, (jbyte *)buf);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_coverart, arr);
    (*env)->DeleteLocalRef(env, arr);
}

static void _audio_remote_control_id(void *cls, const char *dacp_id, const char *active_remote) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    jstring jdacp = (*env)->NewStringUTF(env, dacp_id ? dacp_id : "");
    jstring jremote = (*env)->NewStringUTF(env, active_remote ? active_remote : "");
    if (!jdacp || !jremote) {
        if (jdacp) (*env)->DeleteLocalRef(env, jdacp);
        if (jremote) (*env)->DeleteLocalRef(env, jremote);
        return;
    }
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_dacp_id, jdacp, jremote);
    (*env)->DeleteLocalRef(env, jdacp);
    (*env)->DeleteLocalRef(env, jremote);
}

static void _audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !start || !curr || !end) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_progress,
                           (jlong)*start, (jlong)*curr, (jlong)*end);
}

static void _mirror_video_running(void *cls, bool running) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    LOGI("mirror running: %d", running);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_audio_only, (jboolean)!running);
}

static void _register_client(void *cls, const char *device_id, const char *pk_str, const char *name) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    (void)device_id; (void)name;
    if (ctx->registered_count >= 16) {
        LOGI("_register_client: slot full (16), ignoring new client");
        return;
    }
    for (int i = 0; i < ctx->registered_count; i++) {
        if (ctx->registered_keys[i] && strcmp(ctx->registered_keys[i], pk_str) == 0) return;
    }
    ctx->registered_keys[ctx->registered_count++] = strdup(pk_str);
    LOGI("registered client pk (slot %d)", ctx->registered_count);
}

static bool _check_register(void *cls, const char *pk_str) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    for (int i = 0; i < ctx->registered_count; i++) {
        if (ctx->registered_keys[i] && strcmp(ctx->registered_keys[i], pk_str) == 0) return true;
    }
    return false;
}

static int _video_set_codec(void *cls, video_codec_t codec) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    LOGI("video_set_codec: %d (h265_enabled=%d)", codec, ctx->h265_enabled);
    if (codec == VIDEO_CODEC_H265 && !ctx->h265_enabled) return -1;
    return 0;
}

void android_callbacks_fill(raop_callbacks_t *cbs, android_callback_ctx_t *ctx) {
    memset(cbs, 0, sizeof(raop_callbacks_t));
    cbs->cls = ctx;

    cbs->audio_process = _audio_process;
    cbs->video_process = _video_process;
    cbs->video_pause = _video_pause;
    cbs->video_resume = _video_resume;
    cbs->conn_feedback = _conn_feedback;
    cbs->conn_reset = _conn_reset;
    cbs->video_reset = _video_reset;
    cbs->conn_init = _conn_init;
    cbs->conn_destroy = _conn_destroy;
    cbs->conn_teardown = _noop_teardown;
    cbs->audio_flush = _audio_flush;
    cbs->video_flush = _video_flush;
    cbs->audio_set_client_volume = _audio_set_client_volume;
    cbs->audio_set_volume = _audio_set_volume;
    cbs->audio_set_metadata = _audio_set_metadata;
    cbs->audio_set_coverart = _audio_set_coverart;
    cbs->audio_remote_control_id = _audio_remote_control_id;
    cbs->audio_set_progress = _audio_set_progress;
    cbs->audio_get_format = _audio_get_format;
    cbs->video_report_size = _video_report_size;
    cbs->mirror_video_running = _mirror_video_running;
    cbs->display_pin = _display_pin;
    cbs->video_set_codec = _video_set_codec;
    if (ctx->require_pin) {
        cbs->check_register = _check_register;
        cbs->register_client = _register_client;
    }
}

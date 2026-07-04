#ifndef ANDROID_RAOP_CALLBACKS_H
#define ANDROID_RAOP_CALLBACKS_H

#include <jni.h>
#include <semaphore.h>
#include <pthread.h>
#include "raop.h"

#ifdef __cplusplus
extern "C" {
#endif

#define FRAME_POOL_SIZE 8
#define FRAME_BUFFER_SIZE (4 * 1024 * 1024)  /* 4 MB */

typedef struct {
    JavaVM *jvm;
    jobject callback_obj;
    jmethodID on_video_data;      /* (Ljava/nio/ByteBuffer;JZ)V */
    jmethodID on_audio_data;
    jmethodID on_audio_format;
    jmethodID on_video_size;
    jmethodID on_volume_change;
    jmethodID on_conn_init;
    jmethodID on_conn_destroy;
    jmethodID on_conn_reset;
    jmethodID on_display_pin;
    jmethodID on_metadata;
    jmethodID on_coverart;
    jmethodID on_progress;
    jmethodID on_dacp_id;
    jmethodID on_audio_only;
    jmethodID on_video_pause;
    jmethodID on_video_resume;
    jmethodID on_video_reset;
    jmethodID on_audio_flush;
    jmethodID on_video_flush;
    int h265_enabled;
    int require_pin;
    char *registered_keys[16];
    int registered_count;

    /* --- Frame buffer pool (owned by Java FrameBufferPool, addresses cached here) --- */
    jobject frame_buffers[FRAME_POOL_SIZE];   /* GlobalRef to DirectByteBuffer objects */
    uint8_t *frame_addrs[FRAME_POOL_SIZE];     /* Native addresses of the buffers */
    int frame_pool_free[FRAME_POOL_SIZE];       /* Stack of free indices */
    int frame_pool_head;                        /* Top of free stack */
    pthread_mutex_t frame_pool_lock;            /* Protects free stack */
    sem_t frame_pool_sem;                       /* Counting semaphore for available buffers */
    int frame_pool_initialized;                 /* 1 when pool is ready */
} android_callback_ctx_t;

void android_callbacks_init(android_callback_ctx_t *ctx, JNIEnv *env, jobject callback_obj);
void android_callbacks_destroy(android_callback_ctx_t *ctx, JNIEnv *env);
void android_callbacks_fill(raop_callbacks_t *cbs, android_callback_ctx_t *ctx);

/* Initialize the frame buffer pool with DirectByteBuffers from Java side */
int android_frame_pool_init(android_callback_ctx_t *ctx, JNIEnv *env,
                            jobjectArray buffers, jint count);
/* Return a buffer to the free list (called from Java via JNI) */
void android_frame_pool_return(android_callback_ctx_t *ctx, jobject buffer);
/* Destroy the frame buffer pool */
void android_frame_pool_destroy(android_callback_ctx_t *ctx, JNIEnv *env);

#ifdef __cplusplus
}
#endif

#endif

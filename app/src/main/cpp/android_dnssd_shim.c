/*
 * android_dnssd_shim.c — Platform-specific DNS-SD implementation for Android.
 *
 * This file replaces UxPlay's dns_sd.c / dnssd_mdnsd.c backends.
 * It implements only the "private" (platform-specific) functions declared in
 * dnssd.h: dnssd_private_init, dnssd_private_destroy, dnssd_register_raop,
 * dnssd_register_airplay, dnssd_unregister_*, dnssd_get_*_txt, dnssd_error_text.
 *
 * The dispatch layer (dnssd_init, dnssd_destroy, etc.) is in UxPlay's dnssd.c.
 *
 * mDNS registration is NOT done here — it's done in Kotlin via NsdManager.
 * This shim only builds TXT records in memory for the JNI layer to read.
 */

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <assert.h>
#include <android/log.h>

#include "dnssd.h"
#include "dnssdint.h"
#include "global.h"
#include "utils.h"

#define MAX_SERVNAME 256
#define MAX_TXT_ENTRIES 32
#define MAX_TXT_KEY 32
#define MAX_TXT_VAL 128

/* ---- In-memory TXT record ---- */

typedef struct {
    char key[MAX_TXT_KEY];
    char val[MAX_TXT_VAL];
} txt_entry_t;

typedef struct {
    txt_entry_t entries[MAX_TXT_ENTRIES];
    int count;
    int registered;
} txt_record_t;

/* ---- Android-specific private data (stored in dnssd->dnssd_private) ---- */

typedef struct {
    txt_record_t raop_record;
    txt_record_t airplay_record;
    char codec_cn[16];              /* dynamic "cn" value, e.g. "0,1,2,3" */
    char raop_servname[MAX_SERVNAME]; /* "HW@Name" for RAOP service */
    unsigned short raop_port;
    unsigned short airplay_port;
} android_dnssd_private_t;

/* ---- Helper: set or update a TXT key-value pair ---- */

static void _txt_set(txt_record_t *rec, const char *key, const char *val) {
    for (int i = 0; i < rec->count; i++) {
        if (strcmp(rec->entries[i].key, key) == 0) {
            strncpy(rec->entries[i].val, val, MAX_TXT_VAL - 1);
            rec->entries[i].val[MAX_TXT_VAL - 1] = '\0';
            return;
        }
    }
    if (rec->count < MAX_TXT_ENTRIES) {
        strncpy(rec->entries[rec->count].key, key, MAX_TXT_KEY - 1);
        rec->entries[rec->count].key[MAX_TXT_KEY - 1] = '\0';
        strncpy(rec->entries[rec->count].val, val, MAX_TXT_VAL - 1);
        rec->entries[rec->count].val[MAX_TXT_VAL - 1] = '\0';
        rec->count++;
    }
}

/* ================================================================
 * Platform-specific API (called by UxPlay's dnssd.c dispatch layer)
 * ================================================================ */

/* Called by dnssd_init() in dnssd.c to create platform-specific data */
void *
dnssd_private_init(dnssd_t *dnssd_public, int *error)
{
    if (error) *error = DNSSD_ERROR_NOERROR;

    android_dnssd_private_t *priv =
        (android_dnssd_private_t *)calloc(1, sizeof(android_dnssd_private_t));
    if (!priv) {
        if (error) *error = DNSSD_ERROR_OUTOFMEM;
        return NULL;
    }

    /* Default codec list: PCM, ALAC, AAC, AAC-ELD */
    strncpy(priv->codec_cn, RAOP_CN, sizeof(priv->codec_cn) - 1);

    return priv;
}

/* Called by dnssd_destroy() in dnssd.c */
void
dnssd_private_destroy(void *dnssd_private)
{
    if (dnssd_private) {
        free(dnssd_private);
    }
}

int
dnssd_register_raop(dnssd_t *dnssd, unsigned short port)
{
    assert(dnssd);
    assert(dnssd->dnssd_private);

    android_dnssd_private_t *priv =
        (android_dnssd_private_t *)dnssd->dnssd_private;

    priv->raop_port = port;

    char features[22] = {0};
    snprintf(features, sizeof(features), "0x%X,0x%X",
             dnssd->features1, dnssd->features2);

    txt_record_t *rec = &priv->raop_record;
    rec->count = 0;

    _txt_set(rec, "ch", RAOP_CH);
    _txt_set(rec, "cn", priv->codec_cn);
    _txt_set(rec, "da", RAOP_DA);
    _txt_set(rec, "et", RAOP_ET);
    _txt_set(rec, "vv", RAOP_VV);
    _txt_set(rec, "ft", features);
    _txt_set(rec, "am", GLOBAL_MODEL);
    _txt_set(rec, "md", RAOP_MD);
    _txt_set(rec, "rhd", RAOP_RHD);

    switch (dnssd->pin_pw) {
    case 2:
    case 3:
        _txt_set(rec, "pw", "true");
        _txt_set(rec, "sf", "0x84");
        break;
    case 1:
        _txt_set(rec, "pw", "true");
        _txt_set(rec, "sf", "0x8c");
        break;
    default:
        _txt_set(rec, "pw", "false");
        _txt_set(rec, "sf", RAOP_SF);
        break;
    }

    _txt_set(rec, "sr", RAOP_SR);
    _txt_set(rec, "ss", RAOP_SS);
    _txt_set(rec, "sv", RAOP_SV);
    _txt_set(rec, "tp", RAOP_TP);
    _txt_set(rec, "txtvers", RAOP_TXTVERS);
    _txt_set(rec, "vs", RAOP_VS);
    _txt_set(rec, "vn", RAOP_VN);
    if (dnssd->pk) {
        _txt_set(rec, "pk", dnssd->pk);
    }

    /* Build RAOP service name: "HW@Name" */
    if (utils_hwaddr_raop(priv->raop_servname, sizeof(priv->raop_servname),
                          dnssd->hw_addr, dnssd->hw_addr_len) < 0) {
        return -1;
    }
    /* Append @Name using snprintf for safety (already has HW prefix from utils_hwaddr_raop) */
    size_t hw_len = strlen(priv->raop_servname);
    size_t remaining = sizeof(priv->raop_servname) - hw_len;
    if (remaining > 1) {
        snprintf(priv->raop_servname + hw_len, remaining, "@%s", dnssd->name);
    }

    rec->registered = 1;
    return 0;
}

int
dnssd_register_airplay(dnssd_t *dnssd, unsigned short port)
{
    assert(dnssd);
    assert(dnssd->dnssd_private);

    android_dnssd_private_t *priv =
        (android_dnssd_private_t *)dnssd->dnssd_private;

    priv->airplay_port = port;

    char device_id[3 * MAX_HWADDR_LEN];
    char features[22] = {0};
    snprintf(features, sizeof(features), "0x%X,0x%X",
             dnssd->features1, dnssd->features2);

    if (utils_hwaddr_airplay(device_id, sizeof(device_id),
                             dnssd->hw_addr, dnssd->hw_addr_len) < 0) {
        return -1;
    }

    txt_record_t *rec = &priv->airplay_record;
    rec->count = 0;

    _txt_set(rec, "deviceid", device_id);
    _txt_set(rec, "features", features);

    switch (dnssd->pin_pw) {
    case 1:
    case 2:
    case 3:
        _txt_set(rec, "pw", "true");
        break;
    default:
        _txt_set(rec, "pw", "false");
        break;
    }
    _txt_set(rec, "flags", "0x4");
    _txt_set(rec, "model", GLOBAL_MODEL);
    if (dnssd->pk) {
        _txt_set(rec, "pk", dnssd->pk);
    }
    _txt_set(rec, "pi", AIRPLAY_PI);
    _txt_set(rec, "srcvers", AIRPLAY_SRCVERS);
    _txt_set(rec, "vv", AIRPLAY_VV);

    rec->registered = 1;
    return 0;
}

void
dnssd_unregister_raop(dnssd_t *dnssd)
{
    assert(dnssd);
    assert(dnssd->dnssd_private);
    android_dnssd_private_t *priv =
        (android_dnssd_private_t *)dnssd->dnssd_private;
    priv->raop_record.registered = 0;
}

void
dnssd_unregister_airplay(dnssd_t *dnssd)
{
    assert(dnssd);
    assert(dnssd->dnssd_private);
    android_dnssd_private_t *priv =
        (android_dnssd_private_t *)dnssd->dnssd_private;
    priv->airplay_record.registered = 0;
}

/* Not used on Android — TXT records are retrieved via android_dnssd_* helpers */
const char *
dnssd_get_raop_txt(dnssd_t *dnssd, int *length)
{
    (void)dnssd;
    if (length) *length = 0;
    return NULL;
}

const char *
dnssd_get_airplay_txt(dnssd_t *dnssd, int *length)
{
    (void)dnssd;
    if (length) *length = 0;
    return NULL;
}

void
dnssd_error_text(int *dnssd_error, const char *appname)
{
    (void)appname;
    if (!dnssd_error) return;
    switch (*dnssd_error) {
    case DNSSD_ERROR_NOERROR:
        __android_log_print(ANDROID_LOG_INFO, "DNSSD", "No error");
        break;
    case DNSSD_ERROR_HWADDRLEN:
        __android_log_print(ANDROID_LOG_ERROR, "DNSSD", "HW address length is invalid");
        break;
    case DNSSD_ERROR_OUTOFMEM:
        __android_log_print(ANDROID_LOG_ERROR, "DNSSD", "Out of memory");
        break;
    case DNSSD_ERROR_LIBNOTFOUND:
        __android_log_print(ANDROID_LOG_ERROR, "DNSSD", "dnssd library not found");
        break;
    case DNSSD_ERROR_PROCNOTFOUND:
        __android_log_print(ANDROID_LOG_ERROR, "DNSSD", "dnssd procedure not found");
        break;
    case DNSSD_ERROR_BADFEATURES:
        __android_log_print(ANDROID_LOG_ERROR, "DNSSD", "invalid AirPlay features setting");
        break;
    case DNSSD_ERROR_BADNAME:
        __android_log_print(ANDROID_LOG_ERROR, "DNSSD", "invalid AirPlay name");
        break;
    default:
        __android_log_print(ANDROID_LOG_ERROR, "DNSSD", "Unknown error %d", *dnssd_error);
        break;
    }
}

/* ================================================================
 * Android-specific accessors (called by native_bridge.cpp via JNI)
 * These read the TXT records stored in dnssd->dnssd_private.
 * ================================================================ */

#define GET_PRIV(d) \
    ((android_dnssd_private_t *)((d)->dnssd_private))

int
android_dnssd_get_raop_txt_count(dnssd_t *dnssd)
{
    return GET_PRIV(dnssd)->raop_record.count;
}

const char *
android_dnssd_get_raop_txt_key(dnssd_t *dnssd, int index)
{
    android_dnssd_private_t *priv = GET_PRIV(dnssd);
    if (index < 0 || index >= priv->raop_record.count) return NULL;
    return priv->raop_record.entries[index].key;
}

const char *
android_dnssd_get_raop_txt_val(dnssd_t *dnssd, int index)
{
    android_dnssd_private_t *priv = GET_PRIV(dnssd);
    if (index < 0 || index >= priv->raop_record.count) return NULL;
    return priv->raop_record.entries[index].val;
}

int
android_dnssd_get_airplay_txt_count(dnssd_t *dnssd)
{
    return GET_PRIV(dnssd)->airplay_record.count;
}

const char *
android_dnssd_get_airplay_txt_key(dnssd_t *dnssd, int index)
{
    android_dnssd_private_t *priv = GET_PRIV(dnssd);
    if (index < 0 || index >= priv->airplay_record.count) return NULL;
    return priv->airplay_record.entries[index].key;
}

const char *
android_dnssd_get_airplay_txt_val(dnssd_t *dnssd, int index)
{
    android_dnssd_private_t *priv = GET_PRIV(dnssd);
    if (index < 0 || index >= priv->airplay_record.count) return NULL;
    return priv->airplay_record.entries[index].val;
}

void
android_dnssd_set_codecs(dnssd_t *dnssd, int alac, int aac)
{
    android_dnssd_private_t *priv = GET_PRIV(dnssd);
    /* Build cn string: 0=PCM (always), 1=ALAC, 2=AAC, 3=AAC-ELD */
    char buf[16];
    int pos = 0;
    pos += snprintf(buf + pos, sizeof(buf) - pos, "0");
    if (alac) pos += snprintf(buf + pos, sizeof(buf) - pos, ",1");
    if (aac)  pos += snprintf(buf + pos, sizeof(buf) - pos, ",2,3");
    strncpy(priv->codec_cn, buf, sizeof(priv->codec_cn) - 1);
    priv->codec_cn[sizeof(priv->codec_cn) - 1] = '\0';
}

const char *
android_dnssd_get_raop_servname(dnssd_t *dnssd)
{
    return GET_PRIV(dnssd)->raop_servname;
}

unsigned short
android_dnssd_get_raop_port(dnssd_t *dnssd)
{
    return GET_PRIV(dnssd)->raop_port;
}

unsigned short
android_dnssd_get_airplay_port(dnssd_t *dnssd)
{
    return GET_PRIV(dnssd)->airplay_port;
}

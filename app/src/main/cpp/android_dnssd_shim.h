/*
 * android_dnssd_shim.h — Declarations for Android-specific DNS-SD accessors.
 *
 * These functions are implemented in android_dnssd_shim.c and called by
 * native_bridge.cpp to retrieve TXT records and service names for Kotlin.
 */

#ifndef ANDROID_DNSSD_SHIM_H
#define ANDROID_DNSSD_SHIM_H

#include "dnssd.h"

#ifdef __cplusplus
extern "C" {
#endif

/* TXT record accessors (RAOP = audio, AirPlay = video) */
int           android_dnssd_get_raop_txt_count(dnssd_t *dnssd);
const char   *android_dnssd_get_raop_txt_key(dnssd_t *dnssd, int index);
const char   *android_dnssd_get_raop_txt_val(dnssd_t *dnssd, int index);

int           android_dnssd_get_airplay_txt_count(dnssd_t *dnssd);
const char   *android_dnssd_get_airplay_txt_key(dnssd_t *dnssd, int index);
const char   *android_dnssd_get_airplay_txt_val(dnssd_t *dnssd, int index);

/* Service name and port accessors */
const char   *android_dnssd_get_raop_servname(dnssd_t *dnssd);
unsigned short android_dnssd_get_raop_port(dnssd_t *dnssd);
unsigned short android_dnssd_get_airplay_port(dnssd_t *dnssd);

/* Dynamically update the codec list ("cn" TXT field) */
void          android_dnssd_set_codecs(dnssd_t *dnssd, int alac, int aac);

#ifdef __cplusplus
}
#endif

#endif /* ANDROID_DNSSD_SHIM_H */

cmake_minimum_required(VERSION 3.22.1)
project(aircast_native C CXX)

set(CMAKE_C_STANDARD 17)
set(CMAKE_CXX_STANDARD 17)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffile-prefix-map=${CMAKE_SOURCE_DIR}=. -ffile-prefix-map=${CMAKE_BINARY_DIR}=. -ffile-prefix-map=${ANDROID_NDK}=.")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fexceptions -frtti -ffile-prefix-map=${CMAKE_SOURCE_DIR}=. -ffile-prefix-map=${CMAKE_BINARY_DIR}=. -ffile-prefix-map=${ANDROID_NDK}=.")

# ---- Dependency directory (can be overridden via -DAIRCAST_DEPS_DIR=...) ----
if(NOT DEFINED AIRCAST_DEPS_DIR)
    set(AIRCAST_DEPS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party")
endif()
message(STATUS "AirCast deps directory: ${AIRCAST_DEPS_DIR}")

if(NOT EXISTS "${AIRCAST_DEPS_DIR}/UxPlay-master/lib"
        OR NOT EXISTS "${AIRCAST_DEPS_DIR}/openssl-cmake-3"
        OR NOT EXISTS "${AIRCAST_DEPS_DIR}/libplist"
        OR NOT EXISTS "${AIRCAST_DEPS_DIR}/alac/codec")
    message(FATAL_ERROR
        "AIRCAST_DEPS_DIR is not configured or is incomplete: ${AIRCAST_DEPS_DIR}. "
        "Set aircast.deps.dir in local.properties, pass -PaircastDepsDir=..., "
        "or export AIRCAST_DEPS_DIR.")
endif()

set(UXPLAY_LIB ${AIRCAST_DEPS_DIR}/UxPlay-master/lib)

# ---------- OpenSSL (built from source via openssl-cmake) ----------
set(BUILD_OPENSSL ON CACHE BOOL "" FORCE)
set(OPENSSL_BUILD_VERSION "3.4.4" CACHE STRING "" FORCE)
set(CROSS_ANDROID ON CACHE BOOL "" FORCE)
string(REGEX MATCH "[0-9]+" AIRPLAY_ANDROID_API "${ANDROID_PLATFORM}")
set(OPENSSL_PARAMS "-D__ANDROID_API__=${AIRPLAY_ANDROID_API}" CACHE STRING "" FORCE)
set(FORWARD_ANDROID_NDK_ROOT "${ANDROID_NDK}" CACHE STRING "" FORCE)

list(PREPEND CMAKE_MODULE_PATH ${CMAKE_CURRENT_SOURCE_DIR}/cmake)
add_subdirectory(${AIRCAST_DEPS_DIR}/openssl-cmake-3 openssl-cmake)

# ---------- libplist (built from source) ----------
set(PLIST_SRC ${AIRCAST_DEPS_DIR}/libplist/src)
set(PLIST_CNARY ${AIRCAST_DEPS_DIR}/libplist/libcnary)

add_library(plist STATIC
    ${PLIST_SRC}/base64.c
    ${PLIST_SRC}/bplist.c
    ${PLIST_SRC}/bytearray.c
    ${PLIST_SRC}/common.c
    ${PLIST_SRC}/hashtable.c
    ${PLIST_SRC}/jplist.c
    ${PLIST_SRC}/jsmn.c
    ${PLIST_SRC}/oplist.c
    ${PLIST_SRC}/out-default.c
    ${PLIST_SRC}/out-limd.c
    ${PLIST_SRC}/out-plutil.c
    ${PLIST_SRC}/plist.c
    ${PLIST_SRC}/ptrarray.c
    ${PLIST_SRC}/time64.c
    ${PLIST_SRC}/xplist.c
    ${PLIST_CNARY}/cnary.c
    ${PLIST_CNARY}/node.c
    ${PLIST_CNARY}/node_list.c
)
target_include_directories(plist
    PUBLIC ${AIRCAST_DEPS_DIR}/libplist/include
    PRIVATE ${PLIST_SRC} ${PLIST_CNARY}/include
)
target_compile_definitions(plist PRIVATE
    _GNU_SOURCE
    HAVE_STRNDUP
    PACKAGE_VERSION="2.6.0"
)

# ---------- llhttp (embedded in UxPlay) ----------
add_library(llhttp STATIC
    ${UXPLAY_LIB}/llhttp/api.c
    ${UXPLAY_LIB}/llhttp/http.c
    ${UXPLAY_LIB}/llhttp/llhttp.c
)
target_include_directories(llhttp PUBLIC ${UXPLAY_LIB}/llhttp)

# ---------- playfair (embedded in UxPlay) ----------
add_library(playfair STATIC
    ${UXPLAY_LIB}/playfair/hand_garble.c
    ${UXPLAY_LIB}/playfair/modified_md5.c
    ${UXPLAY_LIB}/playfair/omg_hax.c
    ${UXPLAY_LIB}/playfair/playfair.c
    ${UXPLAY_LIB}/playfair/sap_hash.c
)
target_include_directories(playfair PUBLIC ${UXPLAY_LIB}/playfair)

# ---------- UxPlay core (all .c except platform-specific dnssd backends) ----------
set(AIRPLAY_SOURCES
    ${UXPLAY_LIB}/airplay_video.c
    ${UXPLAY_LIB}/byteutils.c
    ${UXPLAY_LIB}/compat.c
    ${UXPLAY_LIB}/crypto.c
    ${UXPLAY_LIB}/dnssd.c
    ${UXPLAY_LIB}/fairplay_playfair.c
    ${UXPLAY_LIB}/http_request.c
    ${UXPLAY_LIB}/http_response.c
    ${UXPLAY_LIB}/httpd.c
    ${UXPLAY_LIB}/logger.c
    ${UXPLAY_LIB}/mirror_buffer.c
    ${UXPLAY_LIB}/netutils.c
    ${UXPLAY_LIB}/pairing.c
    ${UXPLAY_LIB}/raop.c
    ${UXPLAY_LIB}/raop_buffer.c
    ${UXPLAY_LIB}/raop_ntp.c
    ${UXPLAY_LIB}/raop_rtp.c
    ${UXPLAY_LIB}/raop_rtp_mirror.c
    ${UXPLAY_LIB}/srp.c
    ${UXPLAY_LIB}/utils.c
)

# ---------- Apple ALAC reference decoder (Apache 2.0) ----------
set(ALAC_DIR ${AIRCAST_DEPS_DIR}/alac/codec)
set(ALAC_SOURCES
    ${ALAC_DIR}/ALACDecoder.cpp
    ${ALAC_DIR}/ALACBitUtilities.c
    ${ALAC_DIR}/ag_dec.c
    ${ALAC_DIR}/dp_dec.c
    ${ALAC_DIR}/matrix_dec.c
    ${ALAC_DIR}/EndianPortable.c
)

# ---------- Android JNI bridge + dnssd shim ----------
set(BRIDGE_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/android_dnssd_shim.c
    ${CMAKE_CURRENT_SOURCE_DIR}/android_raop_callbacks.c
    ${CMAKE_CURRENT_SOURCE_DIR}/native_bridge.cpp
)

# ---------- Final shared library ----------
add_library(aircast_native SHARED
    ${AIRPLAY_SOURCES}
    ${ALAC_SOURCES}
    ${BRIDGE_SOURCES}
)

target_include_directories(aircast_native PRIVATE
    ${UXPLAY_LIB}
    ${UXPLAY_LIB}/llhttp
    ${UXPLAY_LIB}/playfair
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${ALAC_DIR}
)

target_compile_definitions(aircast_native PRIVATE
    ANDROID
    __STDC_CONSTANT_MACROS
    PLIST_210
    TARGET_RT_LITTLE_ENDIAN=1
)

target_link_libraries(aircast_native
    llhttp
    playfair
    crypto
    plist
    log
    android
)

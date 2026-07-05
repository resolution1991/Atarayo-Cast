/*
 * AirCast build-time identity override for the bundled UxPlay sources.
 *
 * UxPlay's global.h defines GLOBAL_MODEL / GLOBAL_VERSION unconditionally.
 * This header is force-included before any UxPlay source includes global.h,
 * then defines GLOBAL_H so the original header guard keeps the dependency
 * copy from replacing these values.
 */

#ifndef AIRCAST_GLOBAL_OVERRIDE_H
#define AIRCAST_GLOBAL_OVERRIDE_H

#ifndef GLOBAL_H
#define GLOBAL_H

#define GLOBAL_MODEL    "AppleTV6,2"
#define GLOBAL_VERSION  "380.20.1"

#define OLD_PROTOCOL_CLIENT_USER_AGENT_LIST "AirMyPC/2.0;xxx"
#define DECRYPTION_TEST 0
#define MAX_HWADDR_LEN 6

#endif /* GLOBAL_H */

#endif /* AIRCAST_GLOBAL_OVERRIDE_H */

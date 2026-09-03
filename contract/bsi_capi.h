/* bsi_capi.h -- BSI v1 in-process C ABI (contract artifact; mirrored by hash in
 * block-reality and tectonic2). See BSI.md Part D (transport T-A) and Part G.
 *
 * This is transport T-A with the "doorbell turned into a function call": one
 * call carries one frame in and one frame out. Any host+engine build that ships
 * as a shared library exports exactly these symbols; consumers bind them through
 * JNA / ctypes / FFM without any glue code of their own. ABI is append-only:
 * new functions go at the end and bsi_capi_abi_version() is bumped; existing
 * signatures never change (a change is a BSI major bump).
 *
 * Frame layout (little-endian, identical to the frame_v2 prefix):
 *   'F' 'C' | flags u16 | headerLen u32 | payloadLen u32 | header JSON | payload
 * flags bit0 END_OF_RESPONSE, bit1 HAS_PAYLOAD, bit2 BINARY_PAYLOAD.
 */
#ifndef BSI_CAPI_H
#define BSI_CAPI_H
#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define BSI_CAPI_ABI 1u

#if defined(_WIN32) && !defined(BSI_STATIC)
#  ifdef BSI_CAPI_BUILD
#    define BSI_CAPI __declspec(dllexport)
#  else
#    define BSI_CAPI __declspec(dllimport)
#  endif
#elif defined(__GNUC__) && defined(BSI_CAPI_BUILD)
#  define BSI_CAPI __attribute__((visibility("default")))
#else
#  define BSI_CAPI
#endif

/* return codes of bsi_capi_call (transport-level; protocol errors travel as
 * error frames inside a BSI_CAPI_OK reply) */
enum bsi_capi_status {
  BSI_CAPI_OK          = 0,   /* reply frame written to out, *outLen set        */
  BSI_CAPI_NEED_BIGGER = 2,   /* outCap too small; *outNeeded set; request NOT consumed */
  BSI_CAPI_PROTOCOL    = 4,   /* malformed frame (magic/length); nothing written */
  BSI_CAPI_INVALID     = 5    /* h is NULL or closed                             */
};

#define BSI_CAPI_FLAG_END_OF_RESPONSE (1u << 0)
#define BSI_CAPI_FLAG_HAS_PAYLOAD     (1u << 1)
#define BSI_CAPI_FLAG_BINARY_PAYLOAD  (1u << 2)
#define BSI_CAPI_FRAME_PREFIX_BYTES   12

/* ABI generation of this library: == BSI_CAPI_ABI. A consumer refuses to use a
 * library whose value it does not know. */
BSI_CAPI uint32_t bsi_capi_abi_version(void);

/* One session (one BSI state machine: hello -> vocab -> world -> solve...).
 * optionsJson may be NULL or "{}". The accepted keys are the schema's
 * x-capi.openOptions: "log" (0..3), "numThreads" (1..256, this session's default
 * thread count; a solve's body.numThreads overrides it), "probe" (bool) and
 * "assumeCaps" (array of strings) for harvest mode, and any "x-<vendor>" key,
 * which is ignored. An unknown non-x- key, or one whose type or range is wrong,
 * returns NULL: host configuration fails closed for the same reason the wire
 * does (P6). NULL is also returned when the engine will not open.
 * On failure call bsi_capi_last_error(NULL) for the reason. */
BSI_CAPI void* bsi_capi_open(const char* optionsJson);

/* Send one request frame, receive one reply frame. Calls on one handle must be
 * serialised by the caller; different handles are independent. On NEED_BIGGER
 * the same request must be re-sent with a larger buffer (the host keeps no
 * partial state from the refused attempt). */
BSI_CAPI int bsi_capi_call(void* h, const uint8_t* reqFrame, size_t reqLen,
                           uint8_t* out, size_t outCap, size_t* outLen, size_t* outNeeded);

BSI_CAPI void bsi_capi_close(void* h);

/* Diagnostic text of the last non-OK return on this handle (transport-level),
 * valid until the next call on the handle; NULL when none. */
BSI_CAPI const char* bsi_capi_last_error(void* h);

#ifdef __cplusplus
}
#endif
#endif /* BSI_CAPI_H */

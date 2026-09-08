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
  BSI_CAPI_NEED_BIGGER = 2,   /* reply cached; *outNeeded set; retry identical bytes */
  BSI_CAPI_PROTOCOL    = 4,   /* malformed/oversized frame or different pending request */
  BSI_CAPI_INVALID     = 5    /* NULL handle or fatal preparation failure; close/reopen */
};

#define BSI_CAPI_FLAG_END_OF_RESPONSE (1u << 0)
#define BSI_CAPI_FLAG_HAS_PAYLOAD     (1u << 1)
#define BSI_CAPI_FLAG_BINARY_PAYLOAD  (1u << 2)
#define BSI_CAPI_FRAME_PREFIX_BYTES   12
#define BSI_CAPI_MAX_FRAME_BYTES      (256u * 1024u * 1024u)

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

/* Send one request frame, receive one reply frame. Serialise call, last_error
 * and close on one handle; different handles are independent.
 * NEED_BIGGER means the request MAY ALREADY HAVE EXECUTED: the host retains
 * its full request bytes and encoded reply. Retry with byte-identical input
 * (including flags/header/payload); retries copy the cached reply, never execute
 * again. A different request returns PROTOCOL without discarding the pending
 * reply or advancing the session. Successful delivery releases the cache;
 * identical bytes after OK constitute a NEW call, not permanent id de-duplication.
 * Non-OK writes no out bytes and sets *outLen=0. *outNeeded is the reply size for
 * OK/NEED_BIGGER, otherwise zero (both length pointers are optional).
 * Each request/reply frame is bounded by BSI_CAPI_MAX_FRAME_BYTES; pending
 * storage is at most two such frames, excluding engine/writer working memory.
 * Oversized input is refused before dispatch. Oversized output or preparation
 * exceptions invalidate the handle: future calls return INVALID, close/reopen
 * is required. close releases pending storage and must not race with calls.
 * NULL out always probes, regardless of outCap. Never pass a freed handle. */
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
